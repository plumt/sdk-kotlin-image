package net.plumt.image.compress

/**
 * 결과에 **남길** 수 있는 메타데이터(EXIF) 묶음.
 *
 * 기본값은 아무것도 남기지 않는 것이다 — 위치(GPS)·촬영 시각·기기 정보·작성자 등 원본의 메타데이터는 전부 제거된다
 * (재인코딩이 새 파일을 만들기 때문이다). 필요한 묶음만 [ImageCompressorBuilder.preserveMetadata] 로 켠다.
 *
 * 어떤 설정으로도 **복사하지 않는** 것: 원본 방향 값(회전은 픽셀에 이미 반영했고, 묶음을 켜면 방향 태그는 정상 1 로 적는다),
 * 내장 썸네일, MakerNote, UserComment, 기기·렌즈 일련번호, 카메라 소유자 이름, 고유 이미지 ID, 원본의 XMP·IPTC·ICC 프로필.
 * 넓은 색역 사진은 sRGB 로 변환해 디코딩하고, API 34+ 의 HDR 게인맵은 떼어 낸다.
 */
public enum class MetadataGroup {
    /** 위도·경도·고도·방위 등 모든 GPS 태그. 사진 한 장으로 집·직장이 드러날 수 있는 가장 민감한 묶음이다. */
    LOCATION,

    /** 촬영 일시와 시간대 오프셋. */
    CAPTURE_TIME,

    /** 제조사·모델·렌즈·소프트웨어. 기기를 특정하는 지문이 될 수 있다. */
    CAMERA,

    /** 노출 시간·조리개·ISO·초점 거리·플래시 등 촬영 설정. */
    EXPOSURE,

    /** 저작권 문구와 작성자(Artist). 작성자에 실명이 들어 있을 수 있다. */
    COPYRIGHT,
}
