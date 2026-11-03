package net.plumt.image.compress

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 인코딩 크기를 모델로 대체한 가짜 컨텍스트로 탐색 알고리즘만 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetSizeStrategyTest {

    /** 크기 = 픽셀수 * quality / [divisor] (품질에 대해 단조 증가). */
    private class FakeContext(
        override val bitmap: Bitmap,
        override val quality: Int,
        override val format: ImageFormat = ImageFormat.JPEG,
        val divisor: Int = 10,
    ) : EncodeContext {
        val encodedQualities = ArrayList<Int>()
        val scaled = ArrayList<Bitmap>()
        var released = 0

        override fun encode(bitmap: Bitmap, quality: Int): ByteArray {
            encodedQualities.add(quality)
            val q = if (format.supportsQuality) quality else 100
            return ByteArray(max(1, bitmap.width * bitmap.height * q / divisor / 100))
        }

        override fun scaledTo(width: Int, height: Int): Bitmap =
            Bitmap.createScaledBitmap(bitmap, width, height, false).also { scaled.add(it) }

        override fun release(bitmap: Bitmap) {
            released++
        }

        override fun ensureActive() {}
    }

    private fun bitmap(w: Int = 100, h: Int = 100) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test fun 설정_품질이_이미_들어가면_한_번에_끝난다() {
        val ctx = FakeContext(bitmap(), quality = 80) // 10000*80/10/100 = 800B
        val r = TargetSizeStrategy(targetBytes = 1000).compress(ctx)
        assertEquals(1, r.attempts)
        assertEquals(80, r.quality)
        assertEquals(listOf(80), ctx.encodedQualities)
    }

    @Test fun 이분_탐색은_목표_안에서_가장_높은_품질을_찾는다() {
        // 크기 = 10*q 바이트. 목표 555 -> q 최대 55
        val ctx = FakeContext(bitmap(), quality = 100)
        val r = TargetSizeStrategy(targetBytes = 555, minQuality = 10).compress(ctx)
        assertEquals(55, r.quality)
        assertTrue(r.data.size <= 555)
        assertEquals(550, r.data.size)
    }

    @Test fun 모든_목표에서_선형_탐색과_같은_답이며_시도_횟수는_로그_수준() {
        for (target in 100L..1000L step 7) {
            val ctx = FakeContext(bitmap(), quality = 100)
            val r = TargetSizeStrategy(targetBytes = target, minQuality = 10).compress(ctx)
            val expected = (target / 10).toInt().coerceIn(10, 100)
            assertEquals("target=$target", expected, r.quality)
            // 상한 + 하한 + 이분 탐색(범위 90 -> 최대 7회)
            assertTrue("attempts=${r.attempts}", r.attempts <= 2 + 7)
        }
    }

    @Test fun 같은_입력은_같은_결과() {
        val a = TargetSizeStrategy(555, 10).compress(FakeContext(bitmap(), 100))
        val b = TargetSizeStrategy(555, 10).compress(FakeContext(bitmap(), 100))
        assertEquals(a.quality, b.quality)
        assertEquals(a.attempts, b.attempts)
        assertEquals(a.data.size, b.data.size)
    }

    @Test fun 최소_품질로도_넘으면_크기를_줄인다() {
        // 100x100: q10 = 100B. 목표 60B -> 줄여야 한다
        val ctx = FakeContext(bitmap(), quality = 100)
        val r = TargetSizeStrategy(targetBytes = 60, minQuality = 10, dimensionStep = 0.8f, minDimension = 8).compress(ctx)
        assertTrue(r.data.size <= 60)
        assertTrue("너비 ${r.width}", r.width < 100)
        assertEquals(r.width, r.height)
        // 줄인 크기에서도 품질을 다시 최대화한다
        assertTrue("품질 ${r.quality}", r.quality!! >= 10)
        assertTrue(ctx.scaled.isNotEmpty())
    }

    @Test fun 크기를_줄일_때_이전_사본을_돌려준다() {
        val ctx = FakeContext(bitmap(), quality = 100)
        TargetSizeStrategy(targetBytes = 20, minQuality = 10, dimensionStep = 0.7f, minDimension = 8).compress(ctx)
        assertTrue(ctx.released >= 1)
    }

    @Test fun 끝내_못_맞추면_예외에_가장_작은_시도가_담긴다() {
        val ctx = FakeContext(bitmap(), quality = 100)
        val e = assertThrows(TargetSizeNotReachedException::class.java) {
            TargetSizeStrategy(targetBytes = 1, minQuality = 10, dimensionStep = 0.5f, minDimension = 20).compress(ctx)
        }
        assertEquals(1L, e.targetBytes)
        assertTrue(e.smallestBytes > 1)
        assertEquals(20, max(e.width, e.height)) // 최소 크기까지 내려가 본 뒤 포기
    }

    @Test fun 품질이_없는_형식은_크기만_줄인다() {
        val ctx = FakeContext(bitmap(), quality = 80, format = ImageFormat.PNG)
        val r = TargetSizeStrategy(targetBytes = 400, minDimension = 8).compress(ctx)
        assertNull(r.quality)
        assertTrue(r.data.size <= 400)
        assertTrue(ctx.encodedQualities.all { it == 100 })
    }

    @Test fun 최소_품질이_설정_품질보다_높으면_설정_품질을_하한으로_쓴다() {
        val ctx = FakeContext(bitmap(), quality = 20)
        val r = TargetSizeStrategy(targetBytes = 150, minQuality = 30).compress(ctx) // q20 = 200B > 150
        assertTrue(r.data.size <= 150)
        assertTrue(r.width < 100)
    }

    @Test fun 인자_검사() {
        assertThrows(IllegalArgumentException::class.java) { TargetSizeStrategy(0) }
        assertThrows(IllegalArgumentException::class.java) { TargetSizeStrategy(1, minQuality = 0) }
        assertThrows(IllegalArgumentException::class.java) { TargetSizeStrategy(1, minQuality = 101) }
        assertThrows(IllegalArgumentException::class.java) { TargetSizeStrategy(1, dimensionStep = 1f) }
        assertThrows(IllegalArgumentException::class.java) { TargetSizeStrategy(1, dimensionStep = 0f) }
        assertThrows(IllegalArgumentException::class.java) { TargetSizeStrategy(1, minDimension = 0) }
    }

    @Suppress("unused") private fun unusedRound() = 1.5.roundToInt()
}
