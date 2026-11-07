package net.plumt.image.compress

/**
 * 압축은 끝났지만 호출부가 알아야 하는 대체 처리. [CompressedImage.warnings] 로 받는다.
 * 실패를 숨기지 않으려고 둔 신호다 — 결과를 그대로 써도 되는지는 호출부가 판단한다.
 */
public enum class CompressionWarning {
    /**
     * 원본 EXIF 를 읽지 못했다(손상·비정상 길이 필드 등). 픽셀은 정상적으로 디코딩됐고,
     * 방향은 정상(0°)으로 두었으며 [ImageCompressorBuilder.preserveMetadata] 로 켠 태그도 남기지 못했다.
     * 사진이 누워 보이면 이 경고가 원인일 수 있다.
     */
    EXIF_UNREADABLE,
}
