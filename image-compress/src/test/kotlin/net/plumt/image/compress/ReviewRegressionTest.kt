package net.plumt.image.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.0.1 리뷰·실기기에서 나온 결함의 재현 시험. 각 시험은 0.0.1 코드에서 실패했다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }
    private val jpeg = TestImages.noisyJpeg(800, 600)

    @Before fun registerProvider() {
        Robolectric.setupContentProvider(FileBackedProvider::class.java, FileBackedProvider.AUTHORITY)
    }

    // ---------- H2: 손상 EXIF·권한·플랫폼 IAE ----------

    @Test fun 손상된_EXIF_는_압축을_실패시키지_않고_방향을_정상으로_둔다() {
        val broken = TestImages.corruptExifJpeg()
        // 픽셀 자체는 멀쩡하다
        assertTrue(BitmapFactory.decodeByteArray(broken, 0, broken.size) != null)
        val r = run { ImageCompressor { maxDimension = 1000 }.compressOrThrow(broken) }
        assertEquals(64, r.width)
        assertEquals(48, r.height)
    }

    @Test fun 입력_열기의_SecurityException_은_ImageIoException() {
        val source = object : ImageSource {
            override fun open(): InputStream = throw SecurityException("권한 없음")
        }
        val e = assertThrows(ImageIoException::class.java) { run { ImageCompressor().compressOrThrow(source) } }
        assertTrue(e.cause is SecurityException)
    }

    @Test fun 만료된_content_URI_권한은_ImageIoException() {
        FileBackedProvider.reset(tmp.newFile("in.jpg").apply { writeBytes(jpeg) })
        FileBackedProvider.failure = { throw SecurityException("URI 권한 만료") }
        val e = assertThrows(ImageIoException::class.java) { run { ImageCompressor().compressOrThrow(resolver, FileBackedProvider.uri) } }
        assertTrue(e.cause is SecurityException)
    }

    // ---------- M2: 16비트 원본 ----------

    @Test fun 성분_16비트_PNG_도_8비트_ARGB_로_디코딩하고_8비트_PNG_로_낸다() {
        val png = Png16.rgba16(40, 30) { x, _ -> if (x < 20) 0xFFFF00000000FFFFuL.toLong() else 0x00000000FFFFFFFFuL.toLong() }
        val configs = ArrayList<Bitmap.Config?>()
        val c = ImageCompressor {
            format = ImageFormat.PNG
            encoder = Encoder { b, f, q -> configs.add(b.config); Encoder.Default.encode(b, f, q) }
        }
        val r = run { c.compressOrThrow(png) }
        assertEquals(listOf(Bitmap.Config.ARGB_8888), configs)
        assertEquals(8, Png16.bitDepth(r.data))
        val d = BitmapFactory.decodeByteArray(r.data, 0, r.data.size)
        assertEquals(Color.RED, d.getPixel(5, 5))
        assertEquals(Color.BLUE, d.getPixel(35, 5))
    }

    // ---------- M3: Uri 를 여러 번 연다 ----------

    @Test fun content_URI_는_한_번만_연다() {
        FileBackedProvider.reset(tmp.newFile("in.jpg").apply { writeBytes(jpeg) })
        val r = run { ImageCompressor { maxDimension = 400; preserveMetadata = setOf(MetadataGroup.CAMERA) }.compressOrThrow(resolver, FileBackedProvider.uri) }
        assertEquals(400, r.width)
        assertEquals(listOf("r"), FileBackedProvider.modes.toList())
    }

    // ---------- M5: "wt" 를 지원하지 않는 provider ----------

    @Test fun wt_를_거부하는_provider_에는_w_로_쓰고_남은_꼬리를_자른다() {
        val out = tmp.newFile("out.jpg").apply { writeBytes(ByteArray(500_000) { 7 }) }
        FileBackedProvider.reset(out)
        FileBackedProvider.failure = { mode -> if (mode.contains('t')) throw IllegalArgumentException("지원하지 않는 모드: $mode") }
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        run { r.writeTo(resolver, FileBackedProvider.uri) }
        assertArrayEquals(r.data, out.readBytes())
    }

    @Test fun provider_의_런타임_예외는_ImageIoException() {
        FileBackedProvider.reset(tmp.newFile("out.jpg"))
        FileBackedProvider.failure = { throw IllegalStateException("provider 고장") }
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        val e = assertThrows(ImageIoException::class.java) { run { r.writeTo(resolver, FileBackedProvider.uri) } }
        assertTrue(e.cause is IllegalStateException)
    }

    // ---------- M7: 용량 목표 실패까지의 인코딩 횟수 ----------

    @Test fun 도달_불가능한_용량_목표는_적은_인코딩으로_포기한다() {
        var attempts = 0
        val c = ImageCompressor {
            maxDimension = 1920
            targetBytes = 1
            encoder = Encoder { b, _, q -> attempts++; ByteArray(maxOf(2, b.width * b.height * q / 1000)) }
        }
        val e = assertThrows(TargetSizeNotReachedException::class.java) { run { c.compressOrThrow(TestImages.noisyJpeg(1920, 1080)) } }
        assertEquals(64, maxOf(e.width, e.height))
        assertTrue("인코딩 $attempts 회", attempts <= 6)
    }

    // ---------- 실기기 LOW: 보존 시 Orientation=0 ----------

    @Test fun 메타데이터를_보존해도_방향_태그는_정상_1이다() {
        val tagged = TestImages.withExif(jpeg) {
            setAttribute(ExifInterface.TAG_MODEL, "TestModel")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
        }
        val r = run { ImageCompressor { preserveMetadata = setOf(MetadataGroup.CAMERA) }.compressOrThrow(tagged) }
        val exif = ExifInterface(ByteArrayInputStream(r.data))
        assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("1", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    // ---------- LOW 4: 반투명 배경색 ----------

    @Test fun 반투명_배경색도_흰색_위에_합성해_투명이_검게_나오지_않는다() {
        val clear = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) // 전부 투명
        val png = TestImages.encode(clear, Bitmap.CompressFormat.PNG, 100)
        val r = run { ImageCompressor { quality = 100; backgroundColor = 0x00FF0000 }.compressOrThrow(png) }
        val p = BitmapFactory.decodeByteArray(r.data, 0, r.data.size).getPixel(10, 10)
        assertTrue("픽셀 ${Integer.toHexString(p)}", Color.red(p) > 240 && Color.green(p) > 240 && Color.blue(p) > 240)
    }

    // ---------- LOW 9: 프로필 + strategy ----------

    @Test fun 프로필의_용량_목표_뒤에_strategy_를_대입하면_strategy_가_이긴다() {
        val c = ImageCompressor(CompressionProfiles.Upload) { strategy = SinglePassStrategy }
        assertSame(SinglePassStrategy, c.config.strategy)
        val d = ImageCompressor { strategy = SinglePassStrategy; targetBytes = 1000 }
        assertEquals(1000L, (d.config.strategy as TargetSizeStrategy).targetBytes)
    }

    // ---------- LOW 7: 파일 쓰기 ----------

    @Test fun 파일_덮어쓰기는_임시_파일을_남기지_않는다() {
        val dir = tmp.newFolder("out")
        val target = File(dir, "a.jpg").apply { writeBytes(ByteArray(300_000) { 1 }) }
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        run { r.writeTo(target) }
        assertArrayEquals(r.data, target.readBytes())
        assertEquals(listOf("a.jpg"), dir.list()!!.toList())
    }

    @Test fun 파일_쓰기가_실패하면_기존_파일을_지우지_않는다() {
        val dir = tmp.newFolder("ro")
        val target = File(dir, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        // 대상 자리에 디렉터리를 두면 바꿔치기가 실패한다 — 0.0.1 은 이 경우 대상을 지웠다
        val blocked = File(dir, "sub").apply { mkdirs(); File(this, "keep").writeBytes(byteArrayOf(9)) }
        assertThrows(ImageIoException::class.java) { run { r.writeTo(blocked) } }
        assertTrue(File(blocked, "keep").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertFalse(dir.list()!!.any { it.endsWith(".part") })
    }
}
