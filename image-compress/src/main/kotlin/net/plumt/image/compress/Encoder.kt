package net.plumt.image.compress

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Bitmap 을 바이트로 인코딩하는 지점. 기본 구현은 `Bitmap.compress` 다.
 * 다른 인코더(libjpeg-turbo·mozjpeg 바인딩 등)를 끼우려면 구현해 [ImageCompressorBuilder.encoder] 로 넘긴다.
 *
 * 구현은 [bitmap] 을 recycle 하지 않는다. 실패는 예외로 알린다(빈 배열을 돌려주면 [ImageEncodeException] 이 된다).
 */
public fun interface Encoder {
    /**
     * @param quality 1..100. [ImageFormat.supportsQuality] 가 false 인 형식에서는 무시해도 된다
     */
    public fun encode(bitmap: Bitmap, format: ImageFormat, quality: Int): ByteArray

    public companion object {
        /** `Bitmap.compress` 기반 기본 인코더. */
        public val Default: Encoder = Encoder { bitmap, format, quality ->
            val out = ByteArrayOutputStream()
            val ok = bitmap.compress(compressFormatFor(format, Build.VERSION.SDK_INT), quality, out)
            if (!ok) throw ImageEncodeException("Bitmap.compress 가 ${format.name} 인코딩에 실패했다(false 반환)")
            out.toByteArray()
        }
    }
}

/** 형식과 API 레벨에 맞는 플랫폼 상수. WebP 는 API 30 에서 손실/무손실 상수가 생겼다. */
internal fun compressFormatFor(format: ImageFormat, sdkInt: Int): Bitmap.CompressFormat = when (format) {
    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
    ImageFormat.WEBP -> webp(lossless = false, sdkInt = sdkInt)
    ImageFormat.WEBP_LOSSLESS -> webp(lossless = true, sdkInt = sdkInt)
}

// sdkInt 를 인자로 받아 시험할 수 있게 했고, API 30 상수는 sdkInt >= R 분기 안에서만 읽는다
@SuppressLint("NewApi")
@Suppress("DEPRECATION")
private fun webp(lossless: Boolean, sdkInt: Int): Bitmap.CompressFormat =
    if (sdkInt >= Build.VERSION_CODES.R) {
        if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
