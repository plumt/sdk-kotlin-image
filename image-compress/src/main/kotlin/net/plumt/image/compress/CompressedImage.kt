package net.plumt.image.compress

/**
 * 압축 결과와 그 보고. 같은 입력·같은 설정이면 값이 항상 같다. 파일·URI 로 쓰려면 [ImageCompressor.compress] 에 [ImageTarget] 을 함께 넘긴다.
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
    public val warnings: Set<CompressionWarning>,
) {
    /** 결과 크기(바이트). */
    public val sizeInBytes: Int get() = data.size

    /** 결과의 MIME 형식. */
    public val mimeType: String get() = format.mimeType

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
