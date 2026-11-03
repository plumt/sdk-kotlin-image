package net.plumt.image.compress

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * 압축할 이미지의 출처. 크기 확인·디코딩·방향 읽기가 각각 새로 열기 때문에 [open] 은 **여러 번** 불릴 수 있다.
 *
 * 기본 제공: [bytes], [file], [uri], [stream]. 직접 구현해 다른 출처(네트워크 캐시 등)를 붙일 수 있다.
 * [open] 은 블로킹 입출력이며 [ImageCompressor] 가 자기 디스패처에서 부른다.
 */
public interface ImageSource {
    /** 매번 처음부터 읽는 새 스트림을 연다. 호출자가 닫는다. */
    @Throws(IOException::class)
    public fun open(): InputStream

    public companion object {
        /** 메모리의 바이트 배열. 복사하지 않고 그대로 읽는다. */
        public fun bytes(data: ByteArray): ImageSource = object : ImageSource {
            override fun open(): InputStream = ByteArrayInputStream(data)
        }

        /** 파일. 없으면 [ImageIoException] 으로 알려진다. */
        public fun file(file: File): ImageSource = object : ImageSource {
            override fun open(): InputStream = FileInputStream(file)
        }

        /** `content://`·`file://` URI. [resolver] 는 `context.contentResolver` 를 넘긴다. */
        public fun uri(resolver: ContentResolver, uri: Uri): ImageSource = object : ImageSource {
            override fun open(): InputStream =
                resolver.openInputStream(uri) ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")
        }

        /**
         * 한 번만 읽히는 스트림. 여러 번 열어야 하므로 **전부 메모리로 읽어 들인 뒤** [input] 을 닫는다.
         * 읽는 도중 [maxBytes] 를 넘으면 [ImageIoException] 이다(기본 64MiB) — 끝없는 스트림으로 메모리를 소진당하지 않는다.
         *
         * @throws IllegalArgumentException [maxBytes] 가 0 이하일 때
         */
        public fun stream(input: InputStream, maxBytes: Long = DEFAULT_MAX_STREAM_BYTES): ImageSource {
            require(maxBytes > 0) { "maxBytes 는 1 이상이어야 한다: $maxBytes" }
            return BufferedStreamSource(input, maxBytes)
        }

        /** [stream] 의 기본 읽기 한도(64MiB). */
        public const val DEFAULT_MAX_STREAM_BYTES: Long = 64L * 1024 * 1024
    }
}

private class BufferedStreamSource(private val input: InputStream, private val maxBytes: Long) : ImageSource {
    private var loaded: ByteArray? = null
    private var failure: IOException? = null

    @Synchronized
    override fun open(): InputStream {
        failure?.let { throw it }
        loaded?.let { return ByteArrayInputStream(it) }
        try {
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("스트림이 한도 ${maxBytes}B 를 넘었다")
                    out.write(buffer, 0, n)
                }
                loaded = out.toByteArray()
            }
        } catch (e: IOException) {
            failure = e
            throw e
        }
        return ByteArrayInputStream(loaded!!)
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
