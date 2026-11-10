package net.plumt.image.compress

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 압축 결과를 쓸 곳. [ImageCompressor.compress] 에 넘기면 인코딩 직후 압축기의 디스패처에서 [write] 를 부른다.
 *
 * 기본 제공: [file], [uri], [stream]. 직접 구현해 다른 곳(캐시 저장소 등)에 쓸 수 있다.
 * [write] 가 던진 예외는 [ImageIoException] 실패로 바뀐다. `IllegalArgumentException` 만은 프로그래머 오류로 보고 그대로 던진다.
 */
public interface ImageTarget {
    /** [data] 를 쓴다. 블로킹 입출력이다. */
    @Throws(IOException::class)
    public fun write(data: ByteArray)

    /** 기본 제공 대상. */
    public companion object {
        /**
         * [file] 에 쓴다(없으면 상위 폴더까지 만든다, 있으면 덮어쓴다). 같은 폴더의 임시 파일에 다 쓴 뒤 이름을 바꾸므로
         * 도중에 실패하거나 프로세스가 죽어도 반쯤 쓴 파일이 [file] 자리에 남지 않고, 기존 파일도 그대로 남는다.
         */
        public fun file(file: File): ImageTarget = FileTarget(file)

        /**
         * [uri](쓰기 가능한 `content://` 등)에 덮어쓴다. 먼저 `"wt"`(자르고 쓰기)로 열고, provider 가 그 모드를 거부하면
         * `"w"` 로 열어 쓴 뒤 남은 꼬리를 자른다. 열 수 없거나 권한이 없거나 provider 가 어떤 런타임 예외를 던져도 [ImageIoException] 실패다.
         */
        public fun uri(resolver: ContentResolver, uri: Uri): ImageTarget = UriTarget(resolver, uri)

        /** [out] 에 쓰고 flush 한다. [out] 은 닫지 않는다. */
        public fun stream(out: OutputStream): ImageTarget = StreamTarget(out)
    }
}

/**
 * [target] 에 [data] 를 쓴다. 실패는 [ImageIoException] 으로 바꾸고, 직접 만든 대상이 던진 IAE 는 그대로 둔다.
 * 프로그래머 오류만 통과시키는 규칙은 다른 확장 지점과 같다.
 */
internal fun writeOutput(target: ImageTarget, data: ByteArray) {
    try {
        target.write(data)
    } catch (e: ImageIoException) {
        throw e
    } catch (e: IOException) {
        throw ImageIoException("출력에 쓰지 못했다: $target", e)
    } catch (e: SecurityException) {
        throw ImageIoException("출력에 쓸 권한이 없다: $target", e)
    } catch (e: IllegalArgumentException) {
        if (target is BuiltInTarget) throw ImageIoException("출력에 쓰는 중 플랫폼이 실패했다: $target", e)
        throw e
    } catch (e: RuntimeException) {
        throw ImageIoException("출력에 쓰는 중 실패했다: $target", e)
    }
}

/** SDK 가 만든 대상. 이것들이 던진 IAE 는 플랫폼 내부 실패라 감싼다. */
private sealed interface BuiltInTarget : ImageTarget

private class FileTarget(private val file: File) : BuiltInTarget {
    override fun write(data: ByteArray) {
        val target = file.absoluteFile
        var temp: File? = null
        try {
            if (target.isDirectory) throw IOException("디렉터리에는 쓸 수 없다: ${target.name}")
            val dir = target.parentFile ?: throw IOException("상위 폴더가 없다: ${target.name}")
            dir.mkdirs()
            temp = File.createTempFile(".${target.name}.", ".part", dir)
            FileOutputStream(temp).use { out ->
                out.write(data)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("임시 파일을 ${target.name} 으로 바꾸지 못했다")
            temp = null
        } finally {
            temp?.delete()
        }
    }

    override fun toString(): String = "ImageTarget.file(${file.name})"
}

private class UriTarget(private val resolver: ContentResolver, private val uri: Uri) : BuiltInTarget {
    // provider 의 런타임 예외는 그대로 올려 writeOutput 이 ImageIoException 으로 감싸게 한다(cause 가 원래 예외)
    override fun write(data: ByteArray) {
        val truncating = try {
            resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        if (truncating != null) truncating.use { writeAll(it, data) } else writeWithoutTruncateMode(data)
    }

    /** `"wt"` 를 못 쓰는 provider. `"w"` 는 기존 내용을 자르지 않을 수 있어 기술자로 연 경우 직접 길이를 맞춘다. */
    private fun writeWithoutTruncateMode(data: ByteArray) {
        val pfd = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        if (pfd == null) {
            val stream = resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")
            stream.use { writeAll(it, data) }
            return
        }
        pfd.use {
            val out = FileOutputStream(it.fileDescriptor)
            writeAll(out, data)
            val channel = out.channel
            // 파이프처럼 길이가 없는 대상은 자를 것도 없다
            val regular = try {
                channel.size() >= 0
            } catch (e: IOException) {
                false
            }
            if (regular) channel.truncate(data.size.toLong())
        }
    }

    override fun toString(): String = "ImageTarget.uri"
}

private class StreamTarget(private val out: OutputStream) : BuiltInTarget {
    override fun write(data: ByteArray) = writeAll(out, data)

    override fun toString(): String = "ImageTarget.stream"
}

private fun writeAll(out: OutputStream, data: ByteArray) {
    out.write(data)
    out.flush()
}
