package net.plumt.image.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResizerTest {
    @Test fun fit_는_종횡비를_유지하며_줄인다() {
        val p = FitResizer(1920, 1920).plan(4000, 3000)
        assertEquals(1920, p.width)
        assertEquals(1440, p.height)
        assertEquals(4000, p.cropWidth)
    }

    @Test fun fit_는_세로형도_다룬다() {
        val p = FitResizer(1000, 1000).plan(3000, 6000)
        assertEquals(500, p.width)
        assertEquals(1000, p.height)
    }

    @Test fun fit_는_확대하지_않는다() {
        val p = FitResizer(1920, 1920).plan(800, 600)
        assertEquals(800, p.width)
        assertEquals(600, p.height)
    }

    @Test fun fit_업스케일을_켜면_키운다() {
        val p = FitResizer(1000, 1000, allowUpscale = true).plan(400, 200)
        assertEquals(1000, p.width)
        assertEquals(500, p.height)
    }

    @Test fun 가운데_정사각은_짧은_변_기준으로_자른다() {
        val p = CenterSquareResizer(256).plan(4000, 3000)
        assertEquals(500, p.cropLeft)
        assertEquals(0, p.cropTop)
        assertEquals(3000, p.cropWidth)
        assertEquals(3000, p.cropHeight)
        assertEquals(256, p.width)
    }

    @Test fun 정사각은_원본이_작으면_확대하지_않는다() {
        val p = CenterSquareResizer(256).plan(100, 150)
        assertEquals(100, p.width)
        assertEquals(25, p.cropTop)
    }

    @Test fun 정사각_업스케일() {
        assertEquals(256, CenterSquareResizer(256, allowUpscale = true).plan(100, 150).width)
    }

    @Test fun 잘못된_인자는_IAE() {
        assertThrows(IllegalArgumentException::class.java) { FitResizer(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { CenterSquareResizer(-1) }
        assertThrows(IllegalArgumentException::class.java) { ResizePlan(0, 0, 0, 5, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ResizePlan(-1, 0, 5, 5, 1, 1) }
    }
}
