package net.plumt.image.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 플랫폼 디코더·EXIF 판독기를 바꿔 끼워 압축 흐름의 판단만 검증한다.
 * HEIF·AVIF 는 Robolectric 이 디코딩하지 못하므로, 실제 플랫폼 동작(디코더는 회전하지 않음)은 계측 시험이 고정한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineSeamTest {
    /** 실제 BitmapFactory 로 디코딩하되 형식·EXIF 를 원하는 값으로 보고하는 플랫폼. */
    private open class FakePlatform(
        val mime: String = "image/heic",
        val orientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val attributes: Map<String, String> = emptyMap(),
    ) : ImagePlatform {
        var decodedConfig: Bitmap.Config? = null

        override fun decodeBounds(input: InputStream): DecodedBounds {
            val b = ImagePlatform.Default.decodeBounds(input)
            return DecodedBounds(b.width, b.height, mime)
        }

        override fun decode(input: InputStream, sampleSize: Int): Bitmap? =
            ImagePlatform.Default.decode(input, sampleSize).also { decodedConfig = it?.config }

        override fun readExif(input: InputStream, tags: Collection<String>): ExifData =
            ExifData(orientation, attributes.filterKeys { it in tags })
    }

    private fun pipeline(platform: ImagePlatform, block: ImageCompressorBuilder.() -> Unit = {}) =
        CompressionPipeline(ImageCompressorBuilder().apply(block).build(), platform)

    private fun CompressionPipeline.go(data: ByteArray) = run(ImageSource.bytes(data)) {}

    private val marker: ByteArray = run {
        // 60x40, 왼쪽 위 16x16 빨강
        val src = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        for (x in 0 until 60) for (y in 0 until 40) src.setPixel(x, y, if (x < 16 && y < 16) TestImages.RED else TestImages.BLUE)
        TestImages.encode(src, Bitmap.CompressFormat.PNG, 100)
    }

    private fun isRed(p: Int) = (p shr 16 and 0xFF) > 150 && (p and 0xFF) < 120

    // ---------- H1: HEIF·AVIF 도 EXIF 방향을 한 번 적용한다 ----------

    @Test fun HEIC_도_EXIF_방향_6을_한_번만_적용하고_원본_크기를_세로로_보고한다() {
        for (mime in listOf("image/heic", "image/heif", "image/avif")) {
            val r = pipeline(FakePlatform(mime, ExifInterface.ORIENTATION_ROTATE_90)) { format = ImageFormat.PNG }.go(marker)
            assertEquals(mime, 40, r.sourceWidth)
            assertEquals(mime, 60, r.sourceHeight)
            val d = BitmapFactory.decodeByteArray(r.data, 0, r.data.size)
            assertEquals(40, d.width)
            assertEquals(60, d.height)
            // 90도 시계 방향: 왼쪽 위 빨강이 오른쪽 위로 간다 — 두 번 돌면(180도) 오른쪽 아래가 된다
            assertTrue(mime, isRed(d.getPixel(36, 4)))
            assertFalse(mime, isRed(d.getPixel(36, 56)))
            assertFalse(mime, isRed(d.getPixel(4, 4)))
        }
    }

    @Test fun 범위_밖_방향_값은_정상으로_본다() {
        for (o in listOf(0, -1, 9, 1000)) {
            val r = pipeline(FakePlatform("image/jpeg", o)) { format = ImageFormat.PNG }.go(marker)
            assertEquals(60, r.width)
            assertEquals(40, r.height)
        }
    }

    // ---------- H2: EXIF 판독 실패 ----------

    @Test fun EXIF_판독의_런타임_예외와_OOM_은_정상_방향과_경고로_대체한다() {
        val failures = listOf<() -> Nothing>(
            { throw IllegalArgumentException("bad ifd") },
            { throw ArrayIndexOutOfBoundsException(9) },
            { throw OutOfMemoryError("huge tag") },
            { throw IOException("read") },
            { throw UnsupportedOperationException("avif") },
        )
        for (fail in failures) {
            val platform = object : FakePlatform("image/jpeg", ExifInterface.ORIENTATION_ROTATE_90, mapOf(ExifInterface.TAG_MODEL to "M")) {
                override fun readExif(input: InputStream, tags: Collection<String>): ExifData = fail()
            }
            val r = pipeline(platform) { preserveMetadata = setOf(MetadataGroup.CAMERA) }.go(marker)
            assertEquals(60, r.width)
            assertEquals(setOf(CompressionWarning.EXIF_UNREADABLE), r.warnings)
            assertEquals(emptySet<MetadataGroup>(), r.preservedMetadata)
            assertTrue(r.toString().contains("warnings=[EXIF_UNREADABLE]"))
        }
    }

    @Test fun 정상_결과에는_경고가_없고_toString_도_0_0_1_과_같다() {
        val r = pipeline(FakePlatform("image/jpeg")).go(marker)
        assertTrue(r.warnings.isEmpty())
        assertTrue(r.toString().endsWith("preserved=[])"))
    }

    @Test fun 실제_손상_EXIF_는_경고로_표시된다() {
        val broken = TestImages.corruptExifJpeg()
        val result = kotlinx.coroutines.runBlocking { ImageCompressor().compress(broken) }
        assertEquals(setOf(CompressionWarning.EXIF_UNREADABLE), result.warnings)
    }

    // ---------- H2: 예외 분류 ----------

    @Test fun 플랫폼_디코더의_IAE_는_SDK_예외로_감싼다() {
        val platform = object : FakePlatform("image/jpeg") {
            override fun decode(input: InputStream, sampleSize: Int): Bitmap? = throw IllegalArgumentException("platform")
        }
        val e = assertThrows(ImageEncodeException::class.java) { pipeline(platform).go(marker) }
        assertTrue(e.cause is IllegalArgumentException)
    }

    @Test fun 플랫폼_디코더의_SecurityException_은_ImageIoException() {
        val platform = object : FakePlatform("image/jpeg") {
            override fun decodeBounds(input: InputStream): DecodedBounds = throw SecurityException("no")
        }
        assertThrows(ImageIoException::class.java) { pipeline(platform).go(marker) }
    }

    @Test fun 사용자_Resizer_와_전략_인코더의_IAE_는_그대로_나간다() {
        val mine = IllegalArgumentException("mine")
        val viaResizer = assertThrows(IllegalArgumentException::class.java) {
            pipeline(FakePlatform("image/jpeg")) { resizer = Resizer { _, _ -> throw mine } }.go(marker)
        }
        assertSame(mine, viaResizer)
        val viaStrategy = assertThrows(IllegalArgumentException::class.java) {
            pipeline(FakePlatform("image/jpeg")) { strategy = CompressionStrategy { throw mine } }.go(marker)
        }
        assertSame(mine, viaStrategy)
        val viaEncoder = assertThrows(IllegalArgumentException::class.java) {
            pipeline(FakePlatform("image/jpeg")) { encoder = Encoder { _, _, _ -> throw mine } }.go(marker)
        }
        assertSame(mine, viaEncoder)
    }

    // ---------- M1: 축소 후 회전 — 최대 메모리 ----------

    @Test fun 회전과_축소를_한_번에_해_원본_디코딩본_외에는_결과_하나만_잡는다() {
        val jpeg = TestImages.withExif(TestImages.noisyJpeg(800, 600)) { setAttribute(ExifInterface.TAG_ORIENTATION, "6") }
        val p = pipeline(ImagePlatform.Default) { maxDimension = 300 }
        val r = p.go(jpeg)
        assertEquals(225, r.width)
        assertEquals(300, r.height)
        assertEquals(2, r.sampleSize)
        val sampled = 400L * 300 * 4
        val result = 225L * 300 * 4
        // 0.0.1 은 축소 디코딩본(400x300)을 돌린 같은 크기의 사본을 하나 더 만들어 sampled*2 이상이었다
        assertEquals(sampled + result, p.peakBitmapBytes)
    }

    @Test fun 여덟_방향과_자르기를_한_번에_해도_따로_한_것과_픽셀이_같다() {
        val random = Random(11)
        val src = TestImages.noisyBitmap(37, 23)
        val png = TestImages.encode(src, Bitmap.CompressFormat.PNG, 100)
        for (o in 1..8) repeat(6) {
            val swap = Orientation.swapsDimensions(o)
            val ow = if (swap) 23 else 37
            val oh = if (swap) 37 else 23
            val cw = random.nextInt(1, ow + 1)
            val ch = random.nextInt(1, oh + 1)
            val cl = random.nextInt(0, ow - cw + 1)
            val ct = random.nextInt(0, oh - ch + 1)
            val r = pipeline(FakePlatform("image/png", o)) {
                format = ImageFormat.PNG
                resizer = Resizer { _, _ -> ResizePlan(cl, ct, cw, ch, cw, ch) }
            }.go(png)
            val expectedFull = Orientation.apply(src, o)
            val expected = Bitmap.createBitmap(expectedFull, cl, ct, cw, ch)
            val actual = BitmapFactory.decodeByteArray(r.data, 0, r.data.size)
            assertEquals("o=$o", cw, actual.width)
            assertEquals("o=$o", ch, actual.height)
            for (y in 0 until ch) for (x in 0 until cw) {
                assertEquals("o=$o crop=($cl,$ct ${cw}x$ch) ($x,$y)", expected.getPixel(x, y), actual.getPixel(x, y))
            }
        }
    }

    @Test fun rawRect_는_방향_적용_좌표를_원래_좌표로_되돌린다() {
        // 3x2 원본, 방향 적용 후 (0,0) 1x1 이 원본에서 어디인지 — OrientationTest 의 빨강 위치와 역관계
        val expected = mapOf(1 to (0 to 0), 2 to (2 to 0), 3 to (2 to 1), 4 to (0 to 1), 5 to (0 to 0), 6 to (0 to 1), 7 to (2 to 1), 8 to (2 to 0))
        for ((o, xy) in expected) {
            val r = Orientation.rawRect(o, 3, 2, 0, 0, 1, 1)
            assertEquals("o=$o", listOf(xy.first, xy.second, 1, 1), r.toList())
        }
        assertEquals(listOf(1, 0, 1, 2), Orientation.rawRect(6, 3, 2, 0, 1, 2, 1).toList()) // 회전 90: 폭·높이가 바뀐다
    }

    // ---------- M2: 고정밀·넓은 색역 ----------

    @Test fun 디코딩_옵션은_ARGB_8888_과_sRGB_를_요청한다() {
        val o29 = decodeOptions(4, 29)
        assertEquals(4, o29.inSampleSize)
        assertEquals(Bitmap.Config.ARGB_8888, o29.inPreferredConfig)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), o29.inPreferredColorSpace)
        assertEquals(null, decodeOptions(1, 25).inPreferredColorSpace)
    }

    @Test fun 고정밀_디코딩_결과는_방향이_없어도_8비트로_바꾼다() {
        val png = Png16.rgba16(8, 6) { _, _ -> 0xFFFF00000000FFFFuL.toLong() }
        val platform = FakePlatform("image/png")
        val r = pipeline(platform) { format = ImageFormat.PNG }.go(png)
        assertEquals(Bitmap.Config.RGBA_F16, platform.decodedConfig) // 플랫폼은 16비트 원본을 F16 으로 준다
        assertEquals(8, Png16.bitDepth(r.data))
        assertEquals(8, r.width)
    }

    @Test fun 게인맵을_떼어_SDR_로_낸다() {
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        b.gainmap = Gainmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8))
        assertTrue(b.hasGainmap())
        dropGainmap(b, 33)
        assertTrue(b.hasGainmap())
        dropGainmap(b, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertFalse(b.hasGainmap())
        dropGainmap(b, Build.VERSION_CODES.UPSIDE_DOWN_CAKE) // 없을 때도 안전
    }

    // ---------- 한 번만 읽는 EXIF ----------

    @Test fun EXIF_는_방향과_보존_태그를_한_번에_읽는다() {
        var exifReads = 0
        val platform = object : FakePlatform("image/jpeg", ExifInterface.ORIENTATION_NORMAL, mapOf(ExifInterface.TAG_MODEL to "M")) {
            override fun readExif(input: InputStream, tags: Collection<String>): ExifData {
                exifReads++
                assertTrue(ExifInterface.TAG_MODEL in tags)
                return super.readExif(input, tags)
            }
        }
        val r = pipeline(platform) { preserveMetadata = setOf(MetadataGroup.CAMERA) }.go(marker)
        assertEquals(1, exifReads)
        assertEquals(setOf(MetadataGroup.CAMERA), r.preservedMetadata)
        assertEquals("M", ExifInterface(ByteArrayInputStream(r.data)).getAttribute(ExifInterface.TAG_MODEL))
    }
}
