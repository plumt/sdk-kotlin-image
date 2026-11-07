package net.plumt.image.compress

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** 시험용 이미지 생성기. */
internal object TestImages {
    /** 압축이 잘 안 되는 무작위 잡음. */
    fun noisyBitmap(width: Int, height: Int, seed: Int = 7): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 왼쪽 위만 빨강, 나머지는 파랑인 이미지. 방향 변환 뒤 빨강 위치로 변환을 확인한다. */
    fun markerBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height) { BLUE }
        pixels[0] = RED
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().use {
            check(bitmap.compress(format, quality, it))
            it.toByteArray()
        }

    fun noisyJpeg(width: Int, height: Int, seed: Int = 7): ByteArray {
        val b = noisyBitmap(width, height, seed)
        return encode(b).also { b.recycle() }
    }

    /** [jpeg] 에 EXIF 를 써 넣은 새 JPEG. */
    fun withExif(jpeg: ByteArray, edit: ExifInterface.() -> Unit): ByteArray {
        val file = File.createTempFile("exif", ".jpg").apply { writeBytes(jpeg) }
        try {
            ExifInterface(file).apply {
                edit()
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /**
     * EXIF 길이 필드가 망가진 JPEG(고정 시드). 픽셀은 멀쩡하지만 ExifInterface 가 터무니없이 큰 배열을 요구해
     * OutOfMemoryError 를 낸다 — 0.0.1 은 이 한 장 때문에 압축 전체가 실패했다.
     */
    fun corruptExifJpeg(): ByteArray {
        val base = withExif(noisyJpeg(64, 48)) {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_MODEL, "M")
            setLatLong(1.0, 2.0)
        }
        val len = ((base[4].toInt() and 0xff) shl 8) or (base[5].toInt() and 0xff)
        val r = Random(597)
        val b = base.copyOf()
        repeat(1 + r.nextInt(4)) { b[6 + r.nextInt(len - 2)] = r.nextInt(256).toByte() }
        return b
    }

    const val RED: Int = 0xFFFF0000.toInt()
    const val BLUE: Int = 0xFF0000FF.toInt()
}
