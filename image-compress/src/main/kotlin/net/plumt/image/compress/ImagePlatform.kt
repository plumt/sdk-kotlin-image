package net.plumt.image.compress

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/** 디코더가 알려 준 원본 크기(방향 적용 전)와 형식. */
internal class DecodedBounds(val width: Int, val height: Int, val mimeType: String?)

/** 원본 EXIF 에서 읽은 것. [orientation] 은 태그 값 그대로(없으면 1). */
internal class ExifData(val orientation: Int, val attributes: Map<String, String>)

/**
 * 플랫폼 디코더·EXIF 판독기. 압축 흐름을 플랫폼 동작과 떼어 시험하려고 둔 내부 지점이다.
 *
 * 방향은 **형식과 관계없이** EXIF 판독 결과를 SDK 가 직접 픽셀에 적용한다. `BitmapFactory` 는 JPEG 의 EXIF 방향도,
 * HEIF·AVIF 컨테이너의 회전(`irot`)도 픽셀에 반영하지 않는다 — 반영하는 것은 `ImageDecoder` 뿐이다.
 * androidx ExifInterface 는 HEIF 의 회전을 방향 태그로 돌려준다. 이 가정은 실기기 계측 시험이 고정한다.
 */
internal interface ImagePlatform {
    fun decodeBounds(input: InputStream): DecodedBounds

    fun decode(input: InputStream, sampleSize: Int): Bitmap?

    /** [tags] 에 해당하는 값과 방향을 읽는다. 손상된 EXIF 에서는 어떤 예외·오류든 던질 수 있다. */
    fun readExif(input: InputStream, tags: Collection<String>): ExifData

    object Default : ImagePlatform {
        override fun decodeBounds(input: InputStream): DecodedBounds {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, o)
            return DecodedBounds(o.outWidth, o.outHeight, o.outMimeType)
        }

        override fun decode(input: InputStream, sampleSize: Int): Bitmap? =
            BitmapFactory.decodeStream(input, null, decodeOptions(sampleSize, Build.VERSION.SDK_INT))

        override fun readExif(input: InputStream, tags: Collection<String>): ExifData {
            val exif = ExifInterface(input)
            val found = LinkedHashMap<String, String>()
            for (tag in tags) exif.getAttribute(tag)?.let { found[tag] = it }
            return ExifData(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL), found)
        }
    }
}

/**
 * 본 디코딩 옵션. 16비트 PNG·10비트 HEIF/AVIF 도 8비트 ARGB 로 받고(기본값이면 픽셀당 8바이트인 RGBA_F16 이 된다),
 * API 26+ 에서는 넓은 색역(Display P3 등)을 sRGB 로 변환해 받는다 — 결과 파일에 색 프로필이 없어도 같은 색으로 보이게 한다.
 */
// sdkInt 를 인자로 받아 시험할 수 있게 했고, API 26 호출은 sdkInt 분기 안에서만 한다
@SuppressLint("NewApi")
internal fun decodeOptions(sampleSize: Int, sdkInt: Int): BitmapFactory.Options = BitmapFactory.Options().apply {
    inSampleSize = sampleSize
    inPreferredConfig = Bitmap.Config.ARGB_8888
    if (sdkInt >= Build.VERSION_CODES.O) Api26.preferSrgb(this)
}

/** API 34+ 의 HDR 게인맵(Ultra HDR)을 떼어 SDR 결과만 낸다. 게인맵이 없거나 API 34 미만이면 아무것도 하지 않는다. */
// sdkInt 를 인자로 받아 시험할 수 있게 했고, API 34 호출은 sdkInt 분기 안에서만 한다
@SuppressLint("NewApi")
internal fun dropGainmap(bitmap: Bitmap, sdkInt: Int) {
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Api34.dropGainmap(bitmap)
}

@RequiresApi(Build.VERSION_CODES.O)
private object Api26 {
    fun preferSrgb(options: BitmapFactory.Options) {
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object Api34 {
    fun dropGainmap(bitmap: Bitmap) {
        if (bitmap.hasGainmap()) bitmap.gainmap = null
    }
}
