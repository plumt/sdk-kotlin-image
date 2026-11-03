package net.plumt.image.compress

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
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

    /** [file] 에 쓴다(없으면 상위 폴더까지 만든다, 있으면 덮어쓴다). 실패하면 반쯤 쓴 파일을 지우고 [ImageIoException]. */
    public suspend fun writeTo(file: File, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        withContext(dispatcher) {
            try {
                file.absoluteFile.parentFile?.mkdirs()
                file.outputStream().use { writeTo(it) }
            } catch (e: IOException) {
                file.delete()
                throw ImageIoException("파일에 쓰지 못했다: ${file.name}", e)
            } catch (e: ImageIoException) {
                file.delete()
                throw e
            }
        }
    }

    /** [uri](쓰기 가능한 `content://` 등)에 덮어쓴다. 열 수 없으면 [ImageIoException]. */
    public suspend fun writeTo(
        resolver: ContentResolver,
        uri: Uri,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(dispatcher) {
            try {
                val stream = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("URI 를 열 수 없다: $uri")
                stream.use { writeTo(it) }
            } catch (e: IOException) {
                throw ImageIoException("URI 에 쓰지 못했다", e)
            } catch (e: SecurityException) {
                throw ImageIoException("URI 쓰기 권한이 없다", e)
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CompressedImage && format == other.format && width == other.width && height == other.height &&
            quality == other.quality && sourceWidth == other.sourceWidth && sourceHeight == other.sourceHeight &&
            sampleSize == other.sampleSize && attempts == other.attempts &&
            preservedMetadata == other.preservedMetadata && data.contentEquals(other.data)

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
            "source=${sourceWidth}x$sourceHeight, sampleSize=$sampleSize, attempts=$attempts, preserved=$preservedMetadata)"
}
