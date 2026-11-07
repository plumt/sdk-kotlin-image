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
    private val groupTags: Map<MetadataGroup, List<String>> = mapOf(
        MetadataGroup.LOCATION to listOf(
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ),
        MetadataGroup.CAPTURE_TIME to listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ),
        MetadataGroup.CAMERA to listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ),
        MetadataGroup.EXPOSURE to listOf(
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE,
        ),
        MetadataGroup.COPYRIGHT to listOf(ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_ARTIST),
    )

    /** [groups] 에 해당하는 태그 이름. 켠 묶음이 없으면 빈 목록이다. */
    fun tagsFor(groups: Set<MetadataGroup>): List<String> = groups.flatMap { groupTags.getValue(it) }

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
