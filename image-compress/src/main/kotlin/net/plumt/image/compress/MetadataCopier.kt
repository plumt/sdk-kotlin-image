package net.plumt.image.compress

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * 사용자가 켠 [MetadataGroup] 의 태그만 원본에서 읽어 두었다가 인코딩 결과에 쓴다.
 * 허용 목록 방식이다 — 여기 적지 않은 태그는 어떤 경우에도 복사되지 않는다.
 */
internal class MetadataSnapshot(val attributes: Map<String, String>) {
    val isEmpty: Boolean get() = attributes.isEmpty()
}

internal object MetadataCopier {
    /** [groups] 에 해당하는 태그 이름. 켠 묶음이 없으면 빈 목록이다. */
    fun tagsFor(groups: Set<MetadataGroup>): List<String> = groups.flatMap { it.tags }

    /**
     * 인코딩된 [encoded] 에 [snapshot] 을 쓴 새 바이트를 돌려준다. ExifInterface 는 파일에만 쓸 수 있어
     * `java.io.tmpdir`(안드로이드에서는 앱 캐시)에 임시 파일을 만들고 곧 지운다.
     */
    fun apply(encoded: ByteArray, snapshot: MetadataSnapshot, format: ImageFormat): ByteArray {
        if (snapshot.isEmpty) return encoded
        val temp = try {
            File.createTempFile("imgc", ".${format.extension}")
        } catch (e: IOException) {
            throw ImageIoException("메타데이터를 쓸 임시 파일을 만들지 못했다", e)
        }
        try {
            temp.writeBytes(encoded)
            val exif = ExifInterface(temp)
            snapshot.attributes.forEach { (tag, value) -> exif.setAttribute(tag, value) }
            // 방향은 이미 픽셀에 반영했다. 적지 않으면 ExifInterface 가 "정의되지 않음(0)" 을 써 넣는다
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
            return temp.readBytes()
        } catch (e: IOException) {
            throw ImageEncodeException("${format.name} 에 메타데이터를 쓰지 못했다", e)
        } catch (e: RuntimeException) {
            throw ImageEncodeException("${format.name} 에 메타데이터를 쓰지 못했다", e)
        } finally {
            temp.delete()
        }
    }
}
