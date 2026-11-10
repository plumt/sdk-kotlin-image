package net.plumt.image.compress

import androidx.exifinterface.media.ExifInterface
import java.util.Collections

/**
 * 결과에 **남길** 수 있는 메타데이터(EXIF) 묶음. 값이 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 * 같은 묶음은 언제나 같은 인스턴스다.
 *
 * 기본값은 아무것도 남기지 않는 것이다 — 위치(GPS)·촬영 시각·기기 정보·작성자 등 원본의 메타데이터는 전부 제거된다
 * (재인코딩이 새 파일을 만들기 때문이다). 필요한 묶음만 [CompressorConfig.Builder.preserveMetadata] 로 켠다.
 *
 * 어떤 설정으로도 **복사하지 않는** 것: 원본 방향 값(회전은 픽셀에 이미 반영했고, 묶음을 켜면 방향 태그는 정상 1 로 적는다),
 * 내장 썸네일, MakerNote, UserComment, 기기·렌즈 일련번호, 카메라 소유자 이름, 고유 이미지 ID, 원본의 XMP·IPTC·ICC 프로필.
 * 넓은 색역 사진은 sRGB 로 변환해 디코딩하고, API 34+ 의 HDR 게인맵은 떼어 낸다.
 */
public class MetadataGroup internal constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
    /** 이 묶음이 복사하는 EXIF 태그 이름(허용 목록). 여기 없는 태그는 어떤 경우에도 복사되지 않는다. */
    internal val tags: List<String>,
) {
    override fun toString(): String = name

    /** 정의된 묶음과 목록. */
    public companion object {
        private val all = ArrayList<MetadataGroup>()
        private fun of(name: String, tags: List<String>) = MetadataGroup(name, tags).also { all += it }

        /** 위도·경도·고도·방위 등 모든 GPS 태그. 사진 한 장으로 집·직장이 드러날 수 있는 가장 민감한 묶음이다. */
        @JvmField
        public val LOCATION: MetadataGroup = of(
            "LOCATION",
            listOf(
                ExifInterface.TAG_GPS_VERSION_ID,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_SATELLITES,
                ExifInterface.TAG_GPS_STATUS,
                ExifInterface.TAG_GPS_MEASURE_MODE,
                ExifInterface.TAG_GPS_DOP,
                ExifInterface.TAG_GPS_SPEED_REF,
                ExifInterface.TAG_GPS_SPEED,
                ExifInterface.TAG_GPS_TRACK_REF,
                ExifInterface.TAG_GPS_TRACK,
                ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                ExifInterface.TAG_GPS_IMG_DIRECTION,
                ExifInterface.TAG_GPS_MAP_DATUM,
                ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
                ExifInterface.TAG_GPS_DEST_LATITUDE,
                ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
                ExifInterface.TAG_GPS_DEST_LONGITUDE,
                ExifInterface.TAG_GPS_DEST_BEARING_REF,
                ExifInterface.TAG_GPS_DEST_BEARING,
                ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
                ExifInterface.TAG_GPS_DEST_DISTANCE,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
                ExifInterface.TAG_GPS_DIFFERENTIAL,
            ),
        )

        /** 촬영 일시와 시간대 오프셋. */
        @JvmField
        public val CAPTURE_TIME: MetadataGroup = of(
            "CAPTURE_TIME",
            listOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_OFFSET_TIME,
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ),
        )

        /** 제조사·모델·렌즈·소프트웨어. 기기를 특정하는 지문이 될 수 있다. */
        @JvmField
        public val CAMERA: MetadataGroup = of(
            "CAMERA",
            listOf(
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL,
            ),
        )

        /** 노출 시간·조리개·ISO·초점 거리·플래시 등 촬영 설정. */
        @JvmField
        public val EXPOSURE: MetadataGroup = of(
            "EXPOSURE",
            listOf(
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_PROGRAM,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                ExifInterface.TAG_APERTURE_VALUE,
                ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                ExifInterface.TAG_BRIGHTNESS_VALUE,
                ExifInterface.TAG_METERING_MODE,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                ExifInterface.TAG_WHITE_BALANCE,
            ),
        )

        /** 저작권 문구와 작성자(Artist). 작성자에 실명이 들어 있을 수 있다. */
        @JvmField
        public val COPYRIGHT: MetadataGroup = of(
            "COPYRIGHT",
            listOf(
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_ARTIST,
            ),
        )

        /** 정의된 모든 묶음(정의 순서). */
        public val values: List<MetadataGroup> get() = Collections.unmodifiableList(ArrayList(all))
    }
}
