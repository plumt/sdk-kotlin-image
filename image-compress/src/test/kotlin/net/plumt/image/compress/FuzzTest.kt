package net.plumt.image.compress

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 고정 시드 무작위 입력 — 어떤 입력에서도 SDK 예외 또는 정상 결과여야 하고 그 밖의 예외(크래시)는 없어야 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FuzzTest {
    private fun attempt(c: ImageCompressor, data: ByteArray) {
        try {
            val r = runBlocking { c.compressOrThrow(data) }
            assertTrue(r.sizeInBytes > 0 && r.width > 0 && r.height > 0)
        } catch (e: ImageCompressionException) {
            // 허용: SDK 예외
        }
    }

    @Test fun 무작위_바이트() {
        val random = Random(20260101)
        val c = ImageCompressor { maxDimension = 64 }
        repeat(200) { attempt(c, random.nextBytes(random.nextInt(0, 2000))) }
    }

    @Test fun 손상된_JPEG() {
        val random = Random(42)
        val base = TestImages.noisyJpeg(120, 90)
        val c = ImageCompressor { maxDimension = 64 }
        repeat(60) {
            val copy = base.copyOf()
            repeat(random.nextInt(1, 20)) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() }
            attempt(c, if (random.nextBoolean()) copy else copy.copyOf(random.nextInt(1, copy.size)))
        }
    }

    @Test fun 무작위_크기와_설정() {
        val random = Random(7)
        repeat(60) {
            val w = random.nextInt(1, 300)
            val h = random.nextInt(1, 300)
            val src = TestImages.noisyJpeg(w, h, seed = it)
            val format = ImageFormat.values[random.nextInt(ImageFormat.values.size)]
            val c = ImageCompressor {
                this.format = format
                maxWidth = random.nextInt(1, 400)
                maxHeight = random.nextInt(1, 400)
                quality = random.nextInt(1, 101)
                maxPixels = random.nextLong(1, 100_000)
                if (random.nextBoolean()) { minQuality = minOf(30, quality); targetBytes = random.nextLong(50, 20_000); minDimension = 1 }
                if (random.nextInt(4) == 0) centerSquare(random.nextInt(1, 200), random.nextBoolean())
            }
            try {
                val r = runBlocking { c.compressOrThrow(src) }
                assertTrue("$w x $h -> ${r.width}x${r.height}", r.width.toLong() * r.height <= c.config.maxPixels)
                (c.config.strategy as? TargetSizeStrategy)?.let { assertTrue(r.sizeInBytes <= it.targetBytes) }
            } catch (e: TargetSizeNotReachedException) {
                assertTrue(e.smallestBytes > e.targetBytes)
            }
        }
    }

    @Test fun 극단_종횡비() {
        val c = ImageCompressor { maxDimension = 50 }
        for ((w, h) in listOf(1 to 1, 1 to 500, 500 to 1, 2 to 3)) {
            val r = runBlocking { c.compressOrThrow(TestImages.noisyJpeg(w, h)) }
            assertTrue(r.width in 1..50 && r.height in 1..50)
        }
    }
}
