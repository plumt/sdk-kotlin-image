package net.plumt.image.compress

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Bitmap 을 바이트로 인코딩하는 지점. 기본 구현은 `Bitmap.compress` 다.
 * 다른 인코더(libjpeg-turbo·mozjpeg 바인딩 등)를 끼우려면 구현해 [CompressorConfig.Builder.encoder] 로 넘긴다.
 *
 * 구현은 [bitmap] 을 recycle 하지 않는다. 실패는 예외로 알린다 — SDK 가 받아 [CompressionResult.Failure] 로 바꾼다
 * (빈 배열을 돌려주면 [ImageEncodeException] 이 된다). `IllegalArgumentException` 만은 프로그래머 오류로 보고 그대로 던진다.
 */
public fun interface Encoder {
    /**
     * @param format 출력 형식. 앱의 `when` 에는 `else` 를 둔다(형식이 늘 수 있다)
     * @param quality 1..100. [ImageFormat.supportsQuality] 가 false 인 형식에서는 무시해도 된다
     */
    public fun encode(bitmap: Bitmap, format: ImageFormat, quality: Int): ByteArray

    /** 기본 인코더. */
    public companion object {
        /** `Bitmap.compress` 기반 기본 인코더. */
        @JvmField
        public val Default: Encoder = Encoder { bitmap, format, quality ->
            val out = ByteArrayOutputStream()
            val sdk = Build.VERSION.SDK_INT
            val ok = bitmap.compress(compressFormatFor(format, sdk), platformQuality(format, quality, sdk), out)
            if (!ok) throw ImageEncodeException("Bitmap.compress 가 ${format.name} 인코딩에 실패했다(false 반환)")
            out.toByteArray()
        }
    }
}

/** 형식과 API 레벨에 맞는 플랫폼 상수. WebP 는 API 30 에서 손실/무손실 상수가 생겼다. */
internal fun compressFormatFor(format: ImageFormat, sdkInt: Int): Bitmap.CompressFormat = format.compressFormat(sdkInt)

/** 플랫폼 인코더에 넘길 품질([ImageFormat] 정의 참고). */
internal fun platformQuality(format: ImageFormat, quality: Int, sdkInt: Int): Int = format.platformQuality(quality, sdkInt)
