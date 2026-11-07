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

    /**
     * 방향을 적용한 좌표계의 사각형([left], [top], [width]x[height])이 방향 적용 **전**([rawWidth]x[rawHeight]) 좌표계에서
     * 차지하는 사각형. 결과는 `[x, y, w, h]`.
     */
    fun rawRect(orientation: Int, rawWidth: Int, rawHeight: Int, left: Int, top: Int, width: Int, height: Int): IntArray =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> intArrayOf(rawWidth - left - width, top, width, height)
            ExifInterface.ORIENTATION_ROTATE_180 -> intArrayOf(rawWidth - left - width, rawHeight - top - height, width, height)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> intArrayOf(left, rawHeight - top - height, width, height)
            ExifInterface.ORIENTATION_TRANSPOSE -> intArrayOf(top, left, height, width)
            ExifInterface.ORIENTATION_ROTATE_90 -> intArrayOf(top, rawHeight - left - width, height, width)
            ExifInterface.ORIENTATION_TRANSVERSE -> intArrayOf(rawWidth - top - height, rawHeight - left - width, height, width)
            ExifInterface.ORIENTATION_ROTATE_270 -> intArrayOf(rawWidth - top - height, left, height, width)
            else -> intArrayOf(left, top, width, height)
        }
}
