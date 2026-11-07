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
 * 압축할 이미지의 출처. 크기 확인·방향 읽기·디코딩이 각각 처음부터 읽기 때문에 [open] 은 **여러 번** 불릴 수 있다.
 *
 * 기본 제공: [bytes], [file], [uri], [stream]. 직접 구현해 다른 출처(네트워크 캐시 등)를 붙일 수 있다.
 * [open] 은 블로킹 입출력이며 [ImageCompressor] 가 자기 디스패처에서 부른다.
 *
 * 기본 제공 [file]·[uri] 는 압축 한 번에 입력을 **한 번만** 연다 — 탐색 가능한 파일 기술자를 되감아 다시 읽는다.
 * 기술자를 얻지 못하거나 탐색할 수 없으면(파이프 기반 provider 등) [open] 을 여러 번 부르는 방식으로 돌아간다.
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
        public fun file(file: File): ImageSource = FileImageSource(file)

        /**
         * `content://`·`file://` URI. [resolver] 는 `context.contentResolver` 를 넘긴다.
         * 읽기 권한이 없거나 만료됐으면(`SecurityException`) [ImageIoException] 이다.
         */
        public fun uri(resolver: ContentResolver, uri: Uri): ImageSource = UriImageSource(resolver, uri)

        /**
         * 한 번만 읽히는 스트림. 여러 번 열어야 하므로 **전부 메모리로 읽어 들인 뒤** [input] 을 닫는다.
         * 읽는 도중 [maxBytes] 를 넘으면 [ImageIoException] 이다(기본 64MiB) — 끝없는 스트림으로 메모리를 소진당하지 않는다.
         * 메모리는 읽은 크기만큼(64KiB 단위로 반올림) 쓰며 배열을 키우며 복사하지 않는다.
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

private class FileImageSource(private val file: File) : SessionSource {
    override fun open(): InputStream = FileInputStream(file)

    override fun openSession(): InputSession {
        val owner = FileInputStream(file)
        return SeekableFdSession.create(owner.fd, owner) ?: run {
            owner.close()
            ReopeningSession(this)
        }
    }

    override fun toString(): String = "ImageSource.file(${file.name})"
}

private class UriImageSource(private val resolver: ContentResolver, private val uri: Uri) : SessionSource {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")

    override fun openSession(): InputSession {
        // 권한 문제(SecurityException)는 그대로 올려 ImageIoException 이 되게 한다. 기술자를 못 주는 provider 만 스트림으로 돌아간다
        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ReopeningSession(this)
        return SeekableFdSession.create(pfd.fileDescriptor, pfd) ?: run {
            pfd.close()
            ReopeningSession(this)
        }
    }
}

private class BufferedStreamSource(private val input: InputStream, private val maxBytes: Long) : ImageSource {
    private var loaded: ChunkedBytes? = null
    private var failure: IOException? = null

    @Synchronized
    override fun open(): InputStream {
        failure?.let { throw it }
        loaded?.let { return it.openStream() }
        val bytes = try {
            input.use { ChunkedBytes.readFrom(it, maxBytes) }
        } catch (e: IOException) {
            failure = e
            throw e
        }
        loaded = bytes
        return bytes.openStream()
    }
}

/**
 * 고정 크기 조각들에 담은 바이트. 한 배열을 키우며 복사하면 순간 메모리가 읽은 크기의 2~3배가 되므로 조각으로 나눠 담는다.
 */
internal class ChunkedBytes private constructor(private val chunks: List<ByteArray>, val size: Long) {
    /** 실제로 잡은 배열 크기의 합. */
    val capacity: Long get() = chunks.sumOf { it.size.toLong() }

    fun openStream(): InputStream = ChunkedInputStream(chunks, size)

    companion object {
        const val CHUNK_SIZE: Int = 64 * 1024

        /** [input] 을 끝까지 읽는다. [maxBytes] 를 넘으면 IOException. 닫지는 않는다. */
        fun readFrom(input: InputStream, maxBytes: Long, chunkSize: Int = CHUNK_SIZE): ChunkedBytes {
            val chunks = ArrayList<ByteArray>()
            var total = 0L
            var current = ByteArray(chunkSize)
            var filled = 0
            while (true) {
                if (filled == current.size) {
                    chunks.add(current)
                    current = ByteArray(chunkSize)
                    filled = 0
                }
                val n = input.read(current, filled, current.size - filled)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("스트림이 한도 ${maxBytes}B 를 넘었다")
                filled += n
            }
            if (filled > 0) chunks.add(if (filled == current.size) current else current.copyOf(filled))
            return ChunkedBytes(chunks, total)
        }
    }
}

private class ChunkedInputStream(private val chunks: List<ByteArray>, private val size: Long) : InputStream() {
    private var position = 0L
    private var marked = 0L
    private val chunkSize = chunks.firstOrNull()?.size ?: 1

    override fun read(): Int {
        if (position >= size) return -1
        val b = chunks[(position / chunkSize).toInt()][(position % chunkSize).toInt()]
        position++
        return b.toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        if (len == 0) return 0
        if (position >= size) return -1
        var copied = 0
        while (copied < len && position < size) {
            val chunk = chunks[(position / chunkSize).toInt()]
            val inChunk = (position % chunkSize).toInt()
            val n = minOf((len - copied).toLong(), (chunk.size - inChunk).toLong(), size - position).toInt()
            System.arraycopy(chunk, inChunk, b, off + copied, n)
            copied += n
            position += n
        }
        return copied
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val skipped = minOf(n, size - position)
        position += skipped
        return skipped
    }

    override fun available(): Int = (size - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun markSupported(): Boolean = true

    override fun mark(readlimit: Int) {
        marked = position
    }

    override fun reset() {
        position = marked
    }
}
