package net.plumt.image.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompressorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bounds(bytes: ByteArray): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    // ---------- 입력 4종 ----------
    private val jpeg = TestImages.noisyJpeg(800, 600)

    @Test fun 바이트_입력은_최대_크기_안으로_줄이고_보고한다() {
        val r = run { ImageCompressor { maxDimension = 400 }.compressOrThrow(jpeg) }
        assertEquals(400, r.width)
        assertEquals(300, r.height)
        assertEquals(800, r.sourceWidth)
        assertEquals(600, r.sourceHeight)
        assertEquals(1, r.attempts)
        assertEquals(80, r.quality)
        assertEquals(ImageFormat.JPEG, r.format)
        assertEquals("image/jpeg", r.mimeType)
        assertEquals(r.data.size, r.sizeInBytes)
        val b = bounds(r.data)
        assertEquals(400, b.outWidth)
        assertEquals(300, b.outHeight)
    }

    @Test fun 축소_디코딩_배수를_보고한다() {
        val r = run { ImageCompressor { maxDimension = 200 }.compressOrThrow(jpeg) }
        assertEquals(4, r.sampleSize) // 800/4 = 200
        assertEquals(200, r.width)
    }

    @Test fun 파일_입력() {
        val f = tmp.newFile("a.jpg").apply { writeBytes(jpeg) }
        val r = run { ImageCompressor { maxDimension = 400 }.compressOrThrow(f) }
        assertEquals(400, r.width)
    }

    @Test fun 없는_파일은_ImageIoException() {
        val e = assertThrows(ImageIoException::class.java) {
            run { ImageCompressor().compressOrThrow(File(tmp.root, "none.jpg")) }
        }
        assertTrue(e.cause is IOException)
    }

    @Test fun 스트림_입력과_닫힘() {
        var closed = false
        val input = object : ByteArrayInputStream(jpeg) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val r = run { ImageCompressor { maxDimension = 400 }.compressOrThrow(input) }
        assertEquals(400, r.width)
        assertTrue(closed)
    }

    @Test fun 스트림_한도를_넘으면_ImageIoException_이고_다시_열어도_같은_실패() {
        val src = ImageSource.stream(ByteArrayInputStream(jpeg), maxBytes = 100)
        repeat(2) {
            assertThrows(IOException::class.java) { src.open() }
        }
        assertThrows(ImageIoException::class.java) {
            run { ImageCompressor().compressOrThrow(ImageSource.stream(ByteArrayInputStream(jpeg), 100)) }
        }
    }

    @Test fun 스트림_한도_인자_검사() {
        val e = assertThrows(IllegalArgumentException::class.java) { ImageSource.stream(ByteArrayInputStream(ByteArray(1)), 0) }
        assertTrue(e.message!!.contains("0"))
    }

    @Test fun 스트림은_여러번_열수_있다() {
        val src = ImageSource.stream(ByteArrayInputStream(jpeg))
        assertArrayEquals(jpeg, src.open().readBytes())
        assertArrayEquals(jpeg, src.open().readBytes())
        assertEquals(64L * 1024 * 1024, ImageSource.DEFAULT_MAX_STREAM_BYTES)
    }

    @Test fun 스트림_읽기_도중_IOException_은_ImageIoException() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
        }
        val e = assertThrows(ImageIoException::class.java) { run { ImageCompressor().compressOrThrow(broken) } }
        assertEquals("boom", e.cause!!.message)
    }

    @Test fun URI_입력_file_스킴() {
        val f = tmp.newFile("b.jpg").apply { writeBytes(jpeg) }
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val r = run { ImageCompressor { maxDimension = 400 }.compressOrThrow(resolver, Uri.fromFile(f)) }
        assertEquals(400, r.width)
    }

    @Test fun URI_를_못_열면_ImageIoException() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        assertThrows(ImageIoException::class.java) {
            run { ImageCompressor().compressOrThrow(resolver, Uri.fromFile(File(tmp.root, "none"))) }
        }
    }

    // ---------- 출력 ----------
    @Test fun 파일로_쓰고_상위_폴더를_만든다() {
        val out = File(tmp.root, "x/y/out.jpg")
        val r = run { ImageCompressor { maxDimension = 100 }.compressToFile(ImageSource.bytes(jpeg), out) }
        assertArrayEquals(r.data, out.readBytes())
    }

    @Test fun URI_로_쓴다() {
        val out = tmp.newFile("o.jpg")
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val r = run { ImageCompressor { maxDimension = 100 }.compressToUri(ImageSource.bytes(jpeg), resolver, Uri.fromFile(out)) }
        assertArrayEquals(r.data, out.readBytes())
    }

    @Test fun 쓸수_없는_URI_는_ImageIoException() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        assertThrows(ImageIoException::class.java) {
            run { r.writeTo(resolver, Uri.parse("content://no.such.authority/x")) }
        }
    }

    @Test fun 디렉터리에는_파일을_쓸수_없다() {
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        assertThrows(ImageIoException::class.java) { run { r.writeTo(tmp.newFolder("dir")) } }
    }

    @Test fun 출력_스트림_실패는_ImageIoException() {
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        val bad = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk")
        }
        val e = assertThrows(ImageIoException::class.java) { r.writeTo(bad) }
        assertEquals("disk", e.cause!!.message)
    }

    @Test fun 출력_스트림에_쓴다() {
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        val o = ByteArrayOutputStream()
        r.writeTo(o)
        assertArrayEquals(r.data, o.toByteArray())
    }

    // ---------- 형식 ----------
    @Test fun 형식별_출력_시그니처() {
        fun sig(f: ImageFormat) = run { ImageCompressor { format = f; maxDimension = 100 }.compressOrThrow(jpeg) }.data
        val j = sig(ImageFormat.JPEG)
        assertEquals(0xFF.toByte(), j[0]); assertEquals(0xD8.toByte(), j[1])
        val p = sig(ImageFormat.PNG)
        assertEquals("PNG", String(p, 1, 3))
        for (f in listOf(ImageFormat.WEBP, ImageFormat.WEBP_LOSSLESS)) {
            val w = sig(f)
            assertEquals("RIFF", String(w, 0, 4))
            assertEquals("WEBP", String(w, 8, 4))
        }
    }

    @Test fun 품질_없는_형식은_quality_가_null() {
        val png = run { ImageCompressor { format = ImageFormat.PNG; maxDimension = 100 }.compressOrThrow(jpeg) }
        assertNull(png.quality)
        val ll = run { ImageCompressor { format = ImageFormat.WEBP_LOSSLESS; maxDimension = 100 }.compressOrThrow(jpeg) }
        assertNull(ll.quality)
    }

    @Test fun 품질이_낮을수록_작다() {
        val hi = run { ImageCompressor { quality = 95; maxDimension = 400 }.compressOrThrow(jpeg) }
        val lo = run { ImageCompressor { quality = 20; maxDimension = 400 }.compressOrThrow(jpeg) }
        assertTrue(lo.sizeInBytes < hi.sizeInBytes)
    }

    @Test fun PNG_입력도_읽는다() {
        val b = TestImages.noisyBitmap(300, 200)
        val png = TestImages.encode(b, Bitmap.CompressFormat.PNG, 100)
        val r = run { ImageCompressor { format = ImageFormat.PNG; maxDimension = 100 }.compressOrThrow(png) }
        assertEquals(100, r.width)
        assertEquals(67, r.height)
    }

    @Test fun 투명_픽셀은_JPEG_에서_배경색_위에_합성된다() {
        val b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) // 전부 투명
        val png = TestImages.encode(b, Bitmap.CompressFormat.PNG, 100)
        val white = run { ImageCompressor { format = ImageFormat.JPEG; quality = 100 }.compressOrThrow(png) }
        val wb = BitmapFactory.decodeByteArray(white.data, 0, white.data.size)
        assertTrue(Color.red(wb.getPixel(10, 10)) > 240 && Color.green(wb.getPixel(10, 10)) > 240)
        val red = run { ImageCompressor { format = ImageFormat.JPEG; quality = 100; backgroundColor = Color.RED }.compressOrThrow(png) }
        val rb = BitmapFactory.decodeByteArray(red.data, 0, red.data.size)
        assertTrue(Color.red(rb.getPixel(10, 10)) > 200 && Color.green(rb.getPixel(10, 10)) < 60)
    }

    @Test fun PNG_는_투명을_유지한다() {
        val b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val png = TestImages.encode(b, Bitmap.CompressFormat.PNG, 100)
        val r = run { ImageCompressor { format = ImageFormat.PNG }.compressOrThrow(png) }
        val d = BitmapFactory.decodeByteArray(r.data, 0, r.data.size)
        assertEquals(0, Color.alpha(d.getPixel(5, 5)))
    }

    // ---------- 크기·픽셀 ----------
    @Test fun 작은_이미지는_확대하지_않지만_다시_인코딩한다() {
        val small = TestImages.noisyJpeg(50, 40)
        val r = run { ImageCompressor().compressOrThrow(small) }
        assertEquals(50, r.width)
        assertEquals(1, r.sampleSize)
        assertNotEquals(small.toList(), r.data.toList())
    }

    @Test fun maxPixels_가_출력_픽셀_수를_제한한다() {
        val r = run { ImageCompressor { maxDimension = 10_000; maxPixels = 10_000 }.compressOrThrow(jpeg) }
        assertTrue(r.width.toLong() * r.height <= 10_000)
        assertTrue(r.width > 50)
        // 종횡비 유지(4:3)
        assertEquals(4.0 / 3, r.width.toDouble() / r.height, 0.05)
    }

    @Test fun 정사각_자르기() {
        val r = run { ImageCompressor { centerSquare(100) }.compressOrThrow(jpeg) }
        assertEquals(100, r.width)
        assertEquals(100, r.height)
    }

    @Test fun 정사각_자르기는_가운데를_고른다() {
        // 가운데 영역만 빨강, 좌우 가장자리는 파랑인 300x100
        val b = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until 300) for (y in 0 until 100) b.setPixel(x, y, if (x in 100 until 200) TestImages.RED else TestImages.BLUE)
        val png = TestImages.encode(b, Bitmap.CompressFormat.PNG, 100)
        val r = run { ImageCompressor { centerSquare(50); format = ImageFormat.PNG }.compressOrThrow(png) }
        val d = BitmapFactory.decodeByteArray(r.data, 0, r.data.size)
        assertEquals(50, d.width)
        assertEquals(TestImages.RED, d.getPixel(0, 0))
        assertEquals(TestImages.RED, d.getPixel(49, 49))
    }

    @Test fun 사용자_Resizer_가_원본_밖을_가리키면_IAE() {
        val c = ImageCompressor { resizer = Resizer { w, h -> ResizePlan(0, 0, w + 1, h, 10, 10) } }
        val e = assertThrows(IllegalArgumentException::class.java) { run { c.compressOrThrow(jpeg) } }
        assertTrue(e.message!!.contains("800x600"))
    }

    // ---------- 방향 ----------
    @Test fun EXIF_방향_6_은_폭_높이를_바꾼다() {
        val rotated = TestImages.withExif(jpeg) { setAttribute(ExifInterface.TAG_ORIENTATION, "6") }
        val r = run { ImageCompressor { maxDimension = 400 }.compressOrThrow(rotated) }
        assertEquals(600, r.sourceWidth)
        assertEquals(800, r.sourceHeight)
        assertEquals(300, r.width)
        assertEquals(400, r.height)
        // 결과에는 방향 태그가 없다 (이미 픽셀에 반영)
        assertFalse(ExifInterface(ByteArrayInputStream(r.data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 2..8)
    }

    @Test fun 방향_6_이면_왼쪽_위_빨강이_오른쪽_위로_간다() {
        val src = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        for (x in 0 until 60) for (y in 0 until 40) src.setPixel(x, y, if (x < 16 && y < 16) TestImages.RED else TestImages.BLUE)
        val j = TestImages.withExif(TestImages.encode(src, Bitmap.CompressFormat.JPEG, 100)) { setAttribute(ExifInterface.TAG_ORIENTATION, "6") }
        val r = run { ImageCompressor { quality = 100; maxDimension = 1000 }.compressOrThrow(j) }
        val d = BitmapFactory.decodeByteArray(r.data, 0, r.data.size)
        assertEquals(40, d.width)
        assertEquals(60, d.height)
        val p = d.getPixel(36, 4)
        assertTrue("red=${Color.red(p)}", Color.red(p) > 150 && Color.blue(p) < 120)
        val q = d.getPixel(4, 4)
        assertTrue(Color.blue(q) > 150)
    }

    // ---------- 오류 ----------
    @Test fun 쓰레기_바이트는_ImageDecodeException() {
        val e = assertThrows(ImageDecodeException::class.java) { run { ImageCompressor().compressOrThrow(ByteArray(64) { 1 }) } }
        assertTrue(e.message!!.contains("크기를 읽을 수 없다"))
    }

    @Test fun 빈_바이트는_ImageDecodeException() {
        assertThrows(ImageDecodeException::class.java) { run { ImageCompressor().compressOrThrow(ByteArray(0)) } }
    }

    @Test fun 잘린_JPEG_는_예외_또는_결과이고_크래시가_아니다() {
        val cut = jpeg.copyOf(jpeg.size / 3)
        try {
            run { ImageCompressor { maxDimension = 100 }.compressOrThrow(cut) }
        } catch (e: ImageCompressionException) {
            // 허용되는 실패 형태
        }
    }

    @Test fun 인코더가_false_를_내면_ImageEncodeException() {
        val c = ImageCompressor { encoder = Encoder { _, _, _ -> throw ImageEncodeException("x") } }
        assertThrows(ImageEncodeException::class.java) { run { c.compressOrThrow(jpeg) } }
    }

    @Test fun 인코더가_빈_배열을_내면_ImageEncodeException() {
        val c = ImageCompressor { encoder = Encoder { _, _, _ -> ByteArray(0) } }
        val e = assertThrows(ImageEncodeException::class.java) { run { c.compressOrThrow(jpeg) } }
        assertTrue(e.message!!.contains("빈 결과"))
    }

    @Test fun 인코더의_런타임_예외는_cause_를_보존해_감싼다() {
        val boom = IllegalStateException("native")
        val c = ImageCompressor { encoder = Encoder { _, _, _ -> throw boom } }
        val e = assertThrows(ImageEncodeException::class.java) { run { c.compressOrThrow(jpeg) } }
        assertSame(boom, e.cause)
    }

    @Test fun 인코더의_IOException_은_ImageEncodeException() {
        val boom = IOException("io")
        val c = ImageCompressor { encoder = Encoder { _, _, _ -> throw boom } }
        assertSame(boom, assertThrows(ImageEncodeException::class.java) { run { c.compressOrThrow(jpeg) } }.cause)
    }

    @Test fun 인코더의_IAE_는_그대로_전달된다() {
        val c = ImageCompressor { encoder = Encoder { _, _, _ -> throw IllegalArgumentException("bad") } }
        assertEquals("bad", assertThrows(IllegalArgumentException::class.java) { run { c.compressOrThrow(jpeg) } }.message)
    }

    @Test fun 인코더의_OOM_은_ImageOutOfMemoryException() {
        val oom = OutOfMemoryError("x")
        val c = ImageCompressor { encoder = Encoder { _, _, _ -> throw oom } }
        assertSame(oom, assertThrows(ImageOutOfMemoryException::class.java) { run { c.compressOrThrow(jpeg) } }.cause)
    }

    @Test fun 용량_목표_불가능은_TargetSizeNotReachedException() {
        val e = assertThrows(TargetSizeNotReachedException::class.java) {
            run { ImageCompressor { targetBytes = 10; minDimension = 16 }.compressOrThrow(jpeg) }
        }
        assertEquals(10L, e.targetBytes)
        assertTrue(e.smallestBytes > 10)
        assertTrue(maxOf(e.width, e.height) <= 16)
        assertTrue(e.message!!.contains("10B"))
    }

    @Test fun 모든_SDK_예외는_상위_타입으로_잡힌다() {
        val all: List<ImageCompressionException> = listOf(
            ImageDecodeException("a"), ImageEncodeException("b"), ImageIoException("c"),
            ImageOutOfMemoryException("d"), TargetSizeNotReachedException(1, 2, 3, 4),
        )
        assertEquals(5, all.size)
        val cause = RuntimeException()
        assertSame(cause, ImageDecodeException("m", cause).cause)
        assertSame(cause, ImageIoException("m", cause).cause)
        assertSame(cause, ImageOutOfMemoryException("m", cause).cause)
        assertSame(cause, ImageEncodeException("m", cause).cause)
    }

    // ---------- 용량 목표 (실제 인코딩) ----------
    @Test fun 실제_인코딩으로_용량_목표를_맞춘다() {
        val base = run { ImageCompressor { quality = 95; maxDimension = 800 }.compressOrThrow(jpeg) }
        val target = base.sizeInBytes / 3L
        val r = run { ImageCompressor { quality = 95; maxDimension = 800; targetBytes = target }.compressOrThrow(jpeg) }
        assertTrue("${r.sizeInBytes} <= $target", r.sizeInBytes <= target)
        assertTrue(r.attempts > 1)
        assertTrue(r.quality!! < 95)
    }

    @Test fun 같은_입력_같은_설정은_같은_결과() {
        val c = ImageCompressor { targetBytes = 30_000; maxDimension = 800 }
        val a = run { c.compressOrThrow(jpeg) }
        val b = run { c.compressOrThrow(jpeg) }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.quality, b.quality)
        assertEquals(a.attempts, b.attempts)
    }

    @Test fun 결과_toString_은_바이트를_싣지_않는다() {
        val r = run { ImageCompressor { maxDimension = 100 }.compressOrThrow(jpeg) }
        assertTrue(r.toString().startsWith("CompressedImage(JPEG 100x75"))
        assertFalse(r.toString().contains("[B@"))
        assertNotEquals(r, "x")
    }

    @Test fun 사용자_전략을_쓴다() {
        val seen = ArrayList<Int>()
        val strategy = CompressionStrategy { ctx ->
            seen.add(ctx.bitmap.width)
            EncodedImage(ctx.encode(ctx.bitmap, 10), ctx.bitmap.width, ctx.bitmap.height, 10, 1)
        }
        val r = run { ImageCompressor { this.strategy = strategy; maxDimension = 100 }.compressOrThrow(jpeg) }
        assertEquals(listOf(100), seen)
        assertEquals(10, r.quality)
    }

    @Test fun SinglePass_는_용량을_보장하지_않는다() {
        val r = run { ImageCompressor { quality = 100; maxDimension = 800 }.compressOrThrow(jpeg) }
        assertEquals(1, r.attempts)
    }

    // ---------- 취소·동시성 ----------
    @Test fun 인코딩_도중_취소하면_CancellationException() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val enc = Encoder { b, f, q ->
                started.complete(Unit)
                runBlocking { proceed.await() }
                Encoder.Default.encode(b, f, q)
            }
            // 매 시도마다 용량을 못 맞춰 여러 번 인코딩하게 한다
            val c = ImageCompressor { encoder = enc; targetBytes = 1; minDimension = 8; dispatcher = Dispatchers.Default }
            val job = launch(Dispatchers.Default) { c.compressOrThrow(jpeg) }
            started.await()
            job.cancel()
            proceed.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
        }
    }

    @Test fun 취소_뒤_전략은_더_인코딩하지_않는다() {
        var calls = 0
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            val enc = Encoder { b, f, q ->
                calls++
                job.cancel()
                Encoder.Default.encode(b, f, q)
            }
            val c = ImageCompressor { encoder = enc; targetBytes = 1; minDimension = 8; dispatcher = Dispatchers.Default }
            job = launch(Dispatchers.Default) { c.compressOrThrow(jpeg) }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(1, calls)
    }

    @Test fun 시작_전에_취소되면_CancellationException_이고_SDK_예외로_감싸지_않는다() {
        val e = assertThrows(CancellationException::class.java) {
            runBlocking {
                val d = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) { ImageCompressor().compressOrThrow(jpeg) }
                d.cancelAndJoin()
                d.await()
            }
        }
        assertFalse(e is ImageCompressionException)
    }

    @Test fun 동시에_여러_번_불러도_결과가_같다() {
        val c = ImageCompressor { maxDimension = 200; targetBytes = 20_000; dispatcher = Dispatchers.Default }
        val results = runBlocking { (1..8).map { async { c.compressOrThrow(jpeg) } }.awaitAll() }
        assertEquals(1, results.toSet().size)
    }

    @Test fun 지정한_디스패처에서_실행된다() {
        var thread: String? = null
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "img-test-thread") }
        try {
            val d = ex.asCoroutineDispatcher()
            val c = ImageCompressor { dispatcher = d; encoder = Encoder { b, f, q -> thread = Thread.currentThread().name; Encoder.Default.encode(b, f, q) } }
            run { c.compressOrThrow(jpeg) }
        } finally {
            ex.shutdown()
        }
        assertTrue(thread!!.startsWith("img-test-thread"))
    }

    @Test fun 비트맵은_실패해도_회수된다() {
        val bitmaps = ArrayList<Bitmap>()
        val c = ImageCompressor {
            encoder = Encoder { b, _, _ -> bitmaps.add(b); throw ImageEncodeException("x") }
        }
        assertThrows(ImageEncodeException::class.java) { run { c.compressOrThrow(jpeg) } }
        assertTrue(bitmaps.isNotEmpty() && bitmaps.all { it.isRecycled })
    }

    @Test fun 성공_뒤에도_중간_비트맵이_회수된다() {
        val bitmaps = ArrayList<Bitmap>()
        val c = ImageCompressor { maxDimension = 200; encoder = Encoder { b, f, q -> bitmaps.add(b); Encoder.Default.encode(b, f, q) } }
        run { c.compressOrThrow(jpeg) }
        assertTrue(bitmaps.all { it.isRecycled })
    }

    @Test fun 대용량_원본도_축소_디코딩으로_작은_비트맵만_올린다() {
        val big = TestImages.noisyJpeg(4000, 3000)
        val dims = ArrayList<Int>()
        val c = ImageCompressor { maxDimension = 500; encoder = Encoder { b, f, q -> dims.add(b.width * b.height); Encoder.Default.encode(b, f, q) } }
        val r = run { c.compressOrThrow(big) }
        assertEquals(8, r.sampleSize)
        assertEquals(500 * 375, dims.single())
    }

    @Test fun 프로필() {
        val up = run { ImageCompressor(CompressionProfiles.Upload).compressOrThrow(jpeg) }
        assertTrue(up.sizeInBytes <= 1536 * 1024)
        val th = run { ImageCompressor(CompressionProfiles.Thumbnail) { maxDimension = 100 }.compressOrThrow(jpeg) }
        assertEquals(100, th.width)
        val av = run { ImageCompressor(CompressionProfiles.Avatar).compressOrThrow(jpeg) }
        assertEquals(256, av.width); assertEquals(256, av.height)
        assertEquals("avatar", CompressionProfiles.Avatar.name)
        assertEquals("CompressionProfile(avatar)", CompressionProfiles.Avatar.toString())
        assertNotNull(CompressorConfig::class)
    }

    @Test fun 사용자_정의_프로필() {
        val banner = CompressionProfile("banner") { maxWidth(300).maxHeight(100).format(ImageFormat.WEBP) }
        val r = run { ImageCompressor(banner).compressOrThrow(jpeg) }
        assertTrue(r.width <= 300 && r.height <= 100)
        assertEquals(ImageFormat.WEBP, r.format)
    }

    @Test fun 뒤에_대입한_값이_프로필을_덮는다() {
        val c = ImageCompressor(CompressionProfiles.Upload) { quality = 50 }
        assertEquals(50, c.config.quality)
        assertTrue(c.config.toString().contains("quality=50"))
    }
}
