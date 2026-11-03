package net.plumt.image.compress

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrientationTest {
    private data class Expect(val orientation: Int, val width: Int, val height: Int, val redX: Int, val redY: Int)

    // 3x2 원본의 왼쪽 위 픽셀(빨강)이 방향 적용 뒤 놓이는 자리
    private val cases = listOf(
        Expect(ExifInterface.ORIENTATION_NORMAL, 3, 2, 0, 0),
        Expect(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 3, 2, 2, 0),
        Expect(ExifInterface.ORIENTATION_ROTATE_180, 3, 2, 2, 1),
        Expect(ExifInterface.ORIENTATION_FLIP_VERTICAL, 3, 2, 0, 1),
        Expect(ExifInterface.ORIENTATION_TRANSPOSE, 2, 3, 0, 0),
        Expect(ExifInterface.ORIENTATION_ROTATE_90, 2, 3, 1, 0),
        Expect(ExifInterface.ORIENTATION_TRANSVERSE, 2, 3, 1, 2),
        Expect(ExifInterface.ORIENTATION_ROTATE_270, 2, 3, 0, 2),
    )

    @Test fun 여덟_방향_모두_픽셀이_기대한_자리로_옮겨진다() {
        for (c in cases) {
            val src = TestImages.markerBitmap(3, 2)
            val out = Orientation.apply(src, c.orientation)
            assertEquals("orientation ${c.orientation} 폭", c.width, out.width)
            assertEquals("orientation ${c.orientation} 높이", c.height, out.height)
            for (y in 0 until out.height) for (x in 0 until out.width) {
                val expected = if (x == c.redX && y == c.redY) TestImages.RED else TestImages.BLUE
                assertEquals("orientation ${c.orientation} ($x,$y)", expected, out.getPixel(x, y))
            }
        }
    }

    @Test fun 정상_방향과_범위_밖은_같은_Bitmap_을_돌려준다() {
        val src = TestImages.markerBitmap(3, 2)
        assertSame(src, Orientation.apply(src, ExifInterface.ORIENTATION_NORMAL))
        assertSame(src, Orientation.apply(src, 0))
        assertSame(src, Orientation.apply(src, 99))
    }

    @Test fun 폭_높이가_바뀌는_방향은_5에서_8() {
        assertEquals(listOf(false, false, false, false, true, true, true, true), (1..8).map { Orientation.swapsDimensions(it) })
    }
}
