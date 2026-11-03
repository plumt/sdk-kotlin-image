package net.plumt.image.compress

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

/** EXIF 방향(1..8)을 픽셀에 반영하는 변환. 1(정상)·범위 밖 값은 변환 없음으로 취급한다. */
internal object Orientation {
    fun swapsDimensions(orientation: Int): Boolean = orientation in 5..8

    fun matrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }

    /** 변환한 새 Bitmap, 변환이 없으면 [source] 그대로. 필터 없이 변환해 90도 단위가 픽셀 그대로 옮겨진다. */
    fun apply(source: Bitmap, orientation: Int): Bitmap {
        val m = matrix(orientation) ?: return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, false)
    }
}
