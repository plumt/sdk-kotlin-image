package net.plumt.image.compress

import java.util.Collections

/**
 * 압축은 끝났지만 호출부가 알아야 하는 대체 처리. [CompressedImage.warnings] 로 받는다.
 * 실패를 숨기지 않으려고 둔 신호다 — 결과를 그대로 써도 되는지는 호출부가 판단한다.
 * 값이 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다. 같은 경고는 언제나 같은 인스턴스다.
 */
public class CompressionWarning internal constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
) {
    override fun toString(): String = name

    /** 정의된 경고와 목록. */
    public companion object {
        private val all = ArrayList<CompressionWarning>()
        private fun of(name: String) = CompressionWarning(name).also { all += it }

        /**
         * 원본 EXIF 를 읽지 못했다(손상·비정상 길이 필드 등). 픽셀은 정상적으로 디코딩됐고,
         * 방향은 정상(0°)으로 두었으며 [CompressorConfig.preserveMetadata] 로 켠 태그도 남기지 못했다.
         * 사진이 누워 보이면 이 경고가 원인일 수 있다.
         */
        @JvmField
        public val EXIF_UNREADABLE: CompressionWarning = of("EXIF_UNREADABLE")

        /** 정의된 모든 경고(정의 순서). */
        public val values: List<CompressionWarning> get() = Collections.unmodifiableList(ArrayList(all))
    }
}
