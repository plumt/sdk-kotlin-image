package net.plumt.image.compress

/**
 * 출력 형식.
 *
 * AVIF·HEIC 는 **읽기 전용**이다. 플랫폼이 디코딩할 수 있는 기기(HEIC 는 API 28+, AVIF 는 API 31+)에서만 입력으로 받고,
 * 출력은 아래 네 가지뿐이다.
 *
 * @property mimeType 결과 파일의 MIME 형식
 * @property extension 점을 뺀 파일 확장자
 * @property supportsQuality 품질 값(1..100)이 결과 용량에 영향을 주는 손실 형식인지
 */
public enum class ImageFormat(
    public val mimeType: String,
    public val extension: String,
    public val supportsQuality: Boolean,
) {
    /** 손실 JPEG. 투명 픽셀은 [ImageCompressorBuilder.backgroundColor] 위에 합성한다. */
    JPEG("image/jpeg", "jpg", true),

    /** 무손실 PNG. 품질 값은 무시되므로 용량 목표는 크기 축소로만 맞춘다. */
    PNG("image/png", "png", false),

    /**
     * 손실 WebP. API 30+ 는 `WEBP_LOSSY`, 미만은 구형 `WEBP` 상수로 인코딩한다.
     * API 29 의 구형 상수는 품질 100 을 무손실로 바꾸므로 기본 인코더는 그때만 99 로 넘긴다.
     */
    WEBP("image/webp", "webp", true),

    /**
     * 무손실 WebP. 기본 인코더는 설정한 품질과 관계없이 100 으로 호출한다.
     * API 30+ 는 `WEBP_LOSSLESS`(품질 = 압축 노력, 100 이 가장 작고 느리다), API 29 는 구형 `WEBP` 상수의 품질 100(무손실)이다.
     * **API 23~28 에는 무손실 경로가 없어** 구형 상수의 품질 100 은 손실 인코딩이다 — 무손실이 꼭 필요하면 [PNG] 를 쓴다.
     */
    WEBP_LOSSLESS("image/webp", "webp", false),
}
