package net.plumt.image.compress

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 압축 결과와 그 보고. 같은 입력·같은 설정이면 값이 항상 같다.
 *
 * @property data 인코딩된 파일 내용(공개 배열을 그대로 노출한다 — 수정하지 않는다)
 * @property format 실제 출력 형식
 * @property width 결과 폭
 * @property height 결과 높이
 * @property quality 최종 품질. 품질이 없는 형식이면 null
 * @property sourceWidth 방향을 적용한 원본 폭
 * @property sourceHeight 방향을 적용한 원본 높이
 * @property sampleSize 디코딩에 쓴 `inSampleSize`(1 이면 축소 디코딩 없음)
 * @property attempts 인코딩 횟수(용량 목표 탐색에서 1 보다 커진다)
 * @property preservedMetadata 결과에 남긴 메타데이터 묶음(기본은 빈 집합 — 전부 제거)
 * @property warnings 결과는 냈지만 대체 처리한 것(보통 빈 집합). [CompressionWarning] 참고
 */
public class CompressedImage internal constructor(
    public val data: ByteArray,
    public val format: ImageFormat,
    public val width: Int,
    public val height: Int,
    public val quality: Int?,
    public val sourceWidth: Int,
    public val sourceHeight: Int,
    public val sampleSize: Int,
    public val attempts: Int,
    public val preservedMetadata: Set<MetadataGroup>,
    public val warnings: Set<CompressionWarning> = emptySet(),
) {
    /** 결과 크기(바이트). */
    public val sizeInBytes: Int get() = data.size

    /** 결과의 MIME 형식. */
    public val mimeType: String get() = format.mimeType

    /** [out] 에 쓴다. 블로킹이며 [out] 은 닫지 않는다. 실패는 [ImageIoException]. */
    public fun writeTo(out: OutputStream) {
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            throw ImageIoException("출력 스트림에 쓰지 못했다", e)
        }
    }

    /**
     * [file] 에 쓴다(없으면 상위 폴더까지 만든다, 있으면 덮어쓴다). 같은 폴더의 임시 파일에 다 쓴 뒤 이름을 바꾸므로
     * 도중에 실패하거나 프로세스가 죽어도 반쯤 쓴 파일이 [file] 자리에 남지 않고, 기존 파일도 그대로 남는다.
     * 실패는 [ImageIoException].
     */
    public suspend fun writeTo(file: File, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        withContext(dispatcher) {
            val target = file.absoluteFile
            var temp: File? = null
            try {
                if (target.isDirectory) throw IOException("디렉터리에는 쓸 수 없다: ${target.name}")
                val dir = target.parentFile ?: throw IOException("상위 폴더가 없다: ${target.name}")
                dir.mkdirs()
                temp = File.createTempFile(".${target.name}.", ".part", dir)
                FileOutputStream(temp).use { out ->
                    writeTo(out)
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) throw IOException("임시 파일을 ${target.name} 으로 바꾸지 못했다")
                temp = null
            } catch (e: IOException) {
                throw ImageIoException("파일에 쓰지 못했다: ${file.name}", e)
            } catch (e: SecurityException) {
                throw ImageIoException("파일 쓰기 권한이 없다: ${file.name}", e)
            } finally {
                temp?.delete()
            }
        }
    }

    /**
     * [uri](쓰기 가능한 `content://` 등)에 덮어쓴다. 먼저 `"wt"`(자르고 쓰기)로 열고, provider 가 그 모드를 거부하면
     * `"w"` 로 열어 쓴 뒤 남은 꼬리를 자른다. 열 수 없거나 권한이 없거나 provider 가 어떤 런타임 예외를 던져도 [ImageIoException].
     */
    public suspend fun writeTo(
        resolver: ContentResolver,
        uri: Uri,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(dispatcher) {
            try {
                val truncating = try {
                    resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: UnsupportedOperationException) {
                    null
                }
                if (truncating != null) truncating.use { writeTo(it) } else writeWithoutTruncateMode(resolver, uri)
            } catch (e: IOException) {
                throw ImageIoException("URI 에 쓰지 못했다", e)
            } catch (e: SecurityException) {
                throw ImageIoException("URI 쓰기 권한이 없다", e)
            } catch (e: ImageIoException) {
                throw e
            } catch (e: RuntimeException) {
                throw ImageIoException("URI 에 쓰는 중 provider 가 실패했다", e)
            }
        }
    }

    /** `"wt"` 를 못 쓰는 provider. `"w"` 는 기존 내용을 자르지 않을 수 있어 기술자로 연 경우 직접 길이를 맞춘다. */
    private fun writeWithoutTruncateMode(resolver: ContentResolver, uri: Uri) {
        val pfd = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        if (pfd == null) {
            val stream = resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")
            stream.use { writeTo(it) }
            return
        }
        pfd.use {
            val out = FileOutputStream(it.fileDescriptor)
            writeTo(out)
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

    override fun equals(other: Any?): Boolean =
        other is CompressedImage && format == other.format && width == other.width && height == other.height &&
            quality == other.quality && sourceWidth == other.sourceWidth && sourceHeight == other.sourceHeight &&
            sampleSize == other.sampleSize && attempts == other.attempts &&
            preservedMetadata == other.preservedMetadata && warnings == other.warnings && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var r = data.contentHashCode()
        r = 31 * r + format.hashCode()
        r = 31 * r + width
        r = 31 * r + height
        r = 31 * r + (quality ?: 0)
        return r
    }

    /** 바이트 내용은 싣지 않는다. */
    override fun toString(): String =
        "CompressedImage(${format.name} ${width}x$height, ${data.size}B, quality=$quality, " +
            "source=${sourceWidth}x$sourceHeight, sampleSize=$sampleSize, attempts=$attempts, preserved=$preservedMetadata" +
            (if (warnings.isEmpty()) ")" else ", warnings=$warnings)")
}
