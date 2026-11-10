package net.plumt.image.compress

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Collections

/**
 * 출력 형식. 값이 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 * 같은 형식은 언제나 같은 인스턴스라 `==`·`===` 로 비교한다.
 *
 * AVIF·HEIC 는 **읽기 전용**이다. 플랫폼이 디코딩할 수 있는 기기(HEIC 는 API 28+, AVIF 는 API 31+)에서만 입력으로 받고,
 * 출력은 [values] 의 네 가지뿐이다.
 */
public class ImageFormat internal constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
    /** 결과 파일의 MIME 형식. */
    public val mimeType: String,
    /** 점을 뺀 파일 확장자. */
    public val extension: String,
    /** 품질 값(1..100)이 결과 용량에 영향을 주는 손실 형식인지. */
    public val supportsQuality: Boolean,
    /** 투명 픽셀을 담을 수 있는지. false 면 [CompressorConfig.backgroundColor] 위에 합성한다. */
    public val supportsAlpha: Boolean,
    internal val platform: PlatformEncoding,
) {
    /** 이 형식과 API 레벨에 맞는 플랫폼 상수. */
    internal fun compressFormat(sdkInt: Int): Bitmap.CompressFormat = platform.compressFormat(sdkInt)

    /** 플랫폼 인코더에 넘길 품질. */
    internal fun platformQuality(quality: Int, sdkInt: Int): Int = platform.quality(quality, sdkInt)

    override fun toString(): String = name

    /** 정의된 형식과 목록. */
    public companion object {
        private val all = ArrayList<ImageFormat>()

        private fun of(name: String, mime: String, ext: String, quality: Boolean, alpha: Boolean, platform: PlatformEncoding) =
            ImageFormat(name, mime, ext, quality, alpha, platform).also { all += it }

        /** 손실 JPEG. 투명 픽셀은 [CompressorConfig.backgroundColor] 위에 합성한다. */
        @JvmField
        public val JPEG: ImageFormat = of("JPEG", "image/jpeg", "jpg", true, false, PlatformEncoding.Fixed(Bitmap.CompressFormat.JPEG))

        /** 무손실 PNG. 품질 값은 무시되므로 용량 목표는 크기 축소로만 맞춘다. */
        @JvmField
        public val PNG: ImageFormat = of("PNG", "image/png", "png", false, true, PlatformEncoding.Fixed(Bitmap.CompressFormat.PNG))

        /**
         * 손실 WebP. API 30+ 는 `WEBP_LOSSY`, 미만은 구형 `WEBP` 상수로 인코딩한다.
         * API 29 의 구형 상수는 품질 100 을 무손실로 바꾸므로 기본 인코더는 그때만 99 로 넘긴다.
         */
        @JvmField
        public val WEBP: ImageFormat = of("WEBP", "image/webp", "webp", true, true, PlatformEncoding.Webp(lossless = false))

        /**
         * 무손실 WebP. 기본 인코더는 설정한 품질과 관계없이 100 으로 호출한다.
         * API 30+ 는 `WEBP_LOSSLESS`(품질 = 압축 노력, 100 이 가장 작고 느리다), API 29 는 구형 `WEBP` 상수의 품질 100(무손실)이다.
         * **API 23~28 에는 무손실 경로가 없어** 구형 상수의 품질 100 은 손실 인코딩이다 — 무손실이 꼭 필요하면 [PNG] 를 쓴다.
         */
        @JvmField
        public val WEBP_LOSSLESS: ImageFormat =
            of("WEBP_LOSSLESS", "image/webp", "webp", false, true, PlatformEncoding.Webp(lossless = true))

        /** 정의된 모든 형식(정의 순서). */
        public val values: List<ImageFormat> get() = Collections.unmodifiableList(ArrayList(all))
    }
}

/** 형식별 플랫폼 인코딩 규칙. 형식 하나를 더할 때 고치는 곳은 그 형식의 정의 한 곳이다. */
internal sealed interface PlatformEncoding {
    fun compressFormat(sdkInt: Int): Bitmap.CompressFormat

    fun quality(quality: Int, sdkInt: Int): Int

    /** API 와 무관한 상수. 품질은 그대로(PNG 는 플랫폼이 무시한다). */
    class Fixed(private val format: Bitmap.CompressFormat) : PlatformEncoding {
        override fun compressFormat(sdkInt: Int): Bitmap.CompressFormat = format

        override fun quality(quality: Int, sdkInt: Int): Int = quality
    }

    /**
     * WebP. API 30 에서 손실/무손실 상수가 생겼다.
     * - 손실: API 29 의 구형 `WEBP` 상수는 품질 100 을 **무손실**로 인코딩한다. 손실 WebP 를 약속했으므로 99 로 낮춘다
     * - 무손실: 항상 100. API 30+ 에서는 압축 노력(클수록 작고 느림)이고, API 29 의 구형 상수는 100 이어야 무손실이다
     */
    class Webp(private val lossless: Boolean) : PlatformEncoding {
        // sdkInt 를 인자로 받아 시험할 수 있게 해 lint 가 분기를 알아보지 못한다. API 30 상수는 Api30 안에서만 읽는다
        @SuppressLint("NewApi")
        @Suppress("DEPRECATION")
        override fun compressFormat(sdkInt: Int): Bitmap.CompressFormat =
            if (sdkInt >= Build.VERSION_CODES.R) Api30.webp(lossless) else Bitmap.CompressFormat.WEBP

        override fun quality(quality: Int, sdkInt: Int): Int = when {
            lossless -> 100
            sdkInt == Build.VERSION_CODES.Q && quality >= 100 -> 99
            else -> quality
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private object Api30 {
    fun webp(lossless: Boolean): Bitmap.CompressFormat =
        if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
}
