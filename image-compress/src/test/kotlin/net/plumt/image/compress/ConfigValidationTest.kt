package net.plumt.image.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidationTest {
    private fun msg(block: ImageCompressorBuilder.() -> Unit): String =
        assertThrows(IllegalArgumentException::class.java) { ImageCompressor(block = block) }.message!!

    @Test fun 기본값() {
        val c = ImageCompressor().config
        assertEquals(ImageFormat.JPEG, c.format)
        assertEquals(80, c.quality)
        assertEquals(4096L * 4096, c.maxPixels)
        assertTrue(c.strategy === SinglePassStrategy)
        assertTrue(c.preserveMetadata.isEmpty())
        assertEquals(0xFFFFFFFF.toInt(), c.backgroundColor)
        assertTrue(c.resizer is FitResizer)
        assertTrue(c.encoder === Encoder.Default)
    }

    @Test fun quality_경계() {
        assertTrue(msg { quality = 0 }.contains("0"))
        assertTrue(msg { quality = 101 }.contains("101"))
        assertTrue(msg { quality = -5 }.contains("-5"))
        assertEquals(1, ImageCompressor { quality = 1 }.config.quality)
        assertEquals(100, ImageCompressor { quality = 100 }.config.quality)
    }

    @Test fun 크기_경계() {
        assertTrue(msg { maxWidth = 0 }.contains("0x1920"))
        assertTrue(msg { maxHeight = -1 }.contains("1920x-1"))
        assertTrue(msg { maxDimension = 0 }.contains("0x0"))
        assertEquals(1, ImageCompressor { maxDimension = 1 }.config.let { (it.resizer as FitResizer).maxWidth })
        assertEquals(7, ImageCompressorBuilder().apply { maxWidth = 7; maxHeight = 3 }.maxDimension)
    }

    @Test fun maxPixels_경계() {
        assertTrue(msg { maxPixels = 0 }.contains("0"))
        assertEquals(1L, ImageCompressor { maxPixels = 1 }.config.maxPixels)
    }

    @Test fun 용량_목표_인자() {
        assertTrue(msg { targetBytes = 0 }.contains("targetBytes"))
        assertTrue(msg { targetBytes = -1 }.contains("-1"))
        assertTrue(msg { targetBytes = 10; minQuality = 0 }.contains("minQuality"))
        assertTrue(msg { targetBytes = 10; dimensionStep = 1f }.contains("dimensionStep"))
        assertTrue(msg { targetBytes = 10; minDimension = 0 }.contains("minDimension"))
        assertTrue(msg { targetBytes = 10; quality = 20; minQuality = 30 }.contains("minQuality(30)"))
    }

    @Test fun 목표와_전략은_나중에_대입한_것이_쓰인다() {
        // 0.0.1 은 둘을 함께 주면 IAE 였다. 0.0.2 부터는 나중 값이 앞의 것을 비운다
        assertTrue(ImageCompressor { targetBytes = 10; strategy = SinglePassStrategy }.config.strategy === SinglePassStrategy)
        assertTrue(ImageCompressor { strategy = SinglePassStrategy; targetBytes = 10 }.config.strategy is TargetSizeStrategy)
        assertTrue(ImageCompressor { targetBytes = 10; strategy = null }.config.strategy is TargetSizeStrategy)
        assertTrue(ImageCompressor { strategy = SinglePassStrategy; targetBytes = null }.config.strategy === SinglePassStrategy)
    }

    @Test fun 목표를_지정하면_TargetSizeStrategy() {
        val s = ImageCompressor { targetBytes = 77; minQuality = 11; dimensionStep = 0.5f; minDimension = 9 }.config.strategy
        s as TargetSizeStrategy
        assertEquals(77L, s.targetBytes); assertEquals(11, s.minQuality); assertEquals(0.5f, s.dimensionStep); assertEquals(9, s.minDimension)
    }

    @Test fun 정사각_크기_인자() {
        assertThrows(IllegalArgumentException::class.java) { ImageCompressor { centerSquare(0) } }
        assertTrue(ImageCompressor { centerSquare(10, allowUpscale = true) }.config.resizer is CenterSquareResizer)
    }

    @Test fun 프로필_이름은_비어있을_수_없다() {
        assertThrows(IllegalArgumentException::class.java) { CompressionProfile(" ") {} }
    }

    @Test fun 메타데이터_집합은_복사해_고정한다() {
        val mutable = mutableSetOf(MetadataGroup.CAMERA)
        val c = ImageCompressor { preserveMetadata = mutable }
        mutable.add(MetadataGroup.LOCATION)
        assertEquals(setOf(MetadataGroup.CAMERA), c.config.preserveMetadata)
    }

    @Test fun 형식_속성() {
        assertEquals(listOf("jpg", "png", "webp", "webp"), ImageFormat.values.map { it.extension })
        assertEquals(listOf(true, false, true, false), ImageFormat.values.map { it.supportsQuality })
        assertEquals("image/webp", ImageFormat.WEBP_LOSSLESS.mimeType)
    }

    @Test fun EncodedImage_인자() {
        assertThrows(IllegalArgumentException::class.java) { EncodedImage(ByteArray(0), 1, 1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { EncodedImage(ByteArray(1), 0, 1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { EncodedImage(ByteArray(1), 1, 1, 101, 1) }
        assertThrows(IllegalArgumentException::class.java) { EncodedImage(ByteArray(1), 1, 1, 1, 0) }
        EncodedImage(ByteArray(1), 1, 1, null, 1)
    }

    @Test fun ResizePlan_toString() {
        assertTrue(ResizePlan.full(4, 3, 2, 1).toString().contains("4x3"))
    }
}

class ResultEqualityTest {
    private fun r(data: ByteArray, q: Int? = 80) = CompressedImage(data, ImageFormat.JPEG, 1, 1, q, 2, 2, 1, 1, emptySet(), emptySet())

    @Test fun 같음과_해시() {
        val a = r(byteArrayOf(1, 2))
        assertEquals(a, r(byteArrayOf(1, 2)))
        assertEquals(a.hashCode(), r(byteArrayOf(1, 2)).hashCode())
        assertEquals(a, a)
        org.junit.Assert.assertNotEquals(a, r(byteArrayOf(1, 3)))
        org.junit.Assert.assertNotEquals(a, r(byteArrayOf(1, 2), q = 70))
        assertEquals(r(byteArrayOf(1), null).hashCode(), r(byteArrayOf(1), null).hashCode())
    }
}
