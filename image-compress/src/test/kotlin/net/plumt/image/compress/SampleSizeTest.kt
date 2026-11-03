package net.plumt.image.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSizeTest {
    @Test fun 목표가_원본_절반_이하면_배수가_커진다() {
        assertEquals(2, SampleSize.compute(4000, 3000, 1920, 1440))
        assertEquals(4, SampleSize.compute(8000, 6000, 1920, 1440))
        assertEquals(16, SampleSize.compute(16000, 12000, 1000, 750))
    }

    @Test fun 원본이_목표보다_작거나_비슷하면_1() {
        assertEquals(1, SampleSize.compute(1000, 800, 1920, 1920))
        assertEquals(1, SampleSize.compute(1920, 1080, 1920, 1080))
        assertEquals(1, SampleSize.compute(3839, 2000, 1920, 1000))
    }

    @Test fun 정확히_두배면_축소한다() {
        assertEquals(2, SampleSize.compute(3840, 2160, 1920, 1080))
    }

    @Test fun 어떤_조합에서도_디코딩_결과가_목표보다_작지_않고_2의_거듭제곱이다() {
        for (w in listOf(100, 640, 1999, 4032, 12000)) for (h in listOf(50, 480, 3024, 9000)) for (out in listOf(1, 64, 512, 1920)) {
            val ow = minOf(out, w)
            val oh = minOf(out, h)
            val s = SampleSize.compute(w, h, ow, oh)
            assertTrue("2의 거듭제곱: $s", s > 0 && (s and (s - 1)) == 0)
            assertTrue("$w x $h -> $ow x $oh, s=$s", w / s >= ow && h / s >= oh)
        }
    }

    @Test fun 정사각_자르기는_짧은_변_기준이라_배수가_작다() {
        // 6000x4000 에서 가운데 4000x4000 → 500: 4000/8=500 까지
        assertEquals(8, SampleSize.compute(4000, 4000, 500, 500))
    }
}
