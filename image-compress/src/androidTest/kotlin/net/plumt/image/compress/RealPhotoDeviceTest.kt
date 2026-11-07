package net.plumt.image.compress

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Debug
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 실기기 계측 테스트. 실제 사진 폴더를 `-PdeviceImageDir=<폴더>` 로 지정하면 테스트 APK assets 로 묶인다(저장소에는 사진을 넣지 않는다).
 * 입력은 앱 전용 파일·MediaStore 사본으로 옮겨 실제 경로(File·content Uri)로 압축하고, 끝나면 사본을 지운다.
 * 입력 파일이 없으면 건너뛴다(CI 는 기기가 없어 이 테스트를 돌리지 않는다).
 *
 * 입력: `cam12.jpg`(EXIF 방향 6), `cam12.heic`(같은 사진의 HEIC, `irot` 90), `big108.jpg`(1억 화소급, 방향 6).
 */
@RunWith(AndroidJUnit4::class)
class RealPhotoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val createdUris = mutableListOf<Uri>()
    private val createdFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
        createdFiles.forEach { it.delete() }
    }

    // ---------- 1. 실제 JPEG(EXIF 방향 6) — File 경로, 업로드 프로필, 메타데이터 제거 ----------

    @Test
    fun realJpeg_file_uploadProfile_stripsMetadata_appliesOrientation() = runBlocking {
        val file = privateCopy("cam12.jpg", injectPrivate = true)
        val before = ExifInterface(file.path)
        assertEquals(6, before.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertTrue("주입한 GPS 가 원본에 있어야 한다", before.latLong != null)

        val compressor = ImageCompressor(CompressionProfiles.Upload)
        val (result, ms, peak) = measured { compressor.compress(file) }
        report("cam12.jpg(File) Upload", result, ms, peak)

        assertTrue(result.width <= 1920 && result.height <= 1920)
        assertTrue("1.5MiB 목표", result.sizeInBytes <= 1536 * 1024)
        assertTrue("방향 6 → 세로가 길어야 한다: ${result.width}x${result.height}", result.height > result.width)
        assertOrientationMatches(read("cam12.jpg"), 6, result.data)
        assertNoPrivateMetadata(result.data, "cam12.jpg Upload")
    }

    @Test
    fun realJpeg_file_webpTarget() = runBlocking {
        val file = privateCopy("cam12.jpg", injectPrivate = true)
        val compressor = ImageCompressor {
            maxDimension = 1920
            format = ImageFormat.WEBP
            quality = 80
            targetBytes = 300_000
        }
        val (result, ms, peak) = measured { compressor.compress(file) }
        report("cam12.jpg(File) WEBP 300KB", result, ms, peak)
        assertTrue(result.sizeInBytes <= 300_000)
        assertTrue(result.height > result.width)
        assertNoPrivateMetadata(result.data, "cam12 webp")
        // WebP 결과가 실제로 디코딩되는지
        val bmp = BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
        assertEquals(result.width, bmp.width)
        assertEquals(result.height, bmp.height)
        bmp.recycle()
    }

    // ---------- 2. content:// Uri 입력·출력 (MediaStore) ----------

    @Test
    fun realJpeg_contentUri_inAndOut() = runBlocking {
        val src = mediaStoreCopy("cam12.jpg", injectPrivate = true)
        val out = newMediaStoreItem("imgsdk_out_${System.nanoTime()}.jpg", "image/jpeg")
        val compressor = ImageCompressor(CompressionProfiles.Upload)
        val (result, ms, peak) = measured { compressor.compressToUri(ImageSource.uri(resolver, src), resolver, out) }
        finishPending(out)
        report("cam12.jpg(content Uri→Uri) Upload", result, ms, peak)

        val written = resolver.openInputStream(out)!!.use { it.readBytes() }
        assertEquals(result.sizeInBytes, written.size)
        assertTrue(result.height > result.width)
        assertNoPrivateMetadata(written, "content Uri 출력")
    }

    // ---------- 3. 방향 1·3·8 (같은 실사진 픽셀에 방향 태그만 바꿔 넣은 사본) ----------

    @Test
    fun orientationVariants() = runBlocking {
        val original = read("cam12.jpg")
        for (o in listOf(1, 3, 8, 6)) {
            val file = privateCopy("cam12.jpg", injectPrivate = false, orientation = o)
            val result = ImageCompressor { maxDimension = 1024 }.compress(file)
            log("orientation=$o → ${result.width}x${result.height}")
            if (o in 5..8) assertTrue(result.height > result.width) else assertTrue(result.width > result.height)
            assertOrientationMatches(original, o, result.data)
        }
    }

    // ---------- 4. HEIC 입력 ----------

    @Test
    fun heic_file_and_uri() = runBlocking {
        assumeTrue("HEIC 입력 없음", exists("cam12.heic"))
        val file = privateCopy("cam12.heic", injectPrivate = false)
        val before = ExifInterface(file.path)
        log("heic 원본 Model 존재=${before.getAttribute(ExifInterface.TAG_MODEL) != null}, orientation=${before.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)}")
        val compressor = ImageCompressor(CompressionProfiles.Upload)
        val (result, ms, peak) = measured { compressor.compress(file) }
        report("cam12.heic(File) Upload", result, ms, peak)
        assertTrue("HEIC 는 세로(3000x4000) 픽셀", result.height > result.width)
        assertTrue(result.width <= 1920 && result.height <= 1920)
        assertNoPrivateMetadata(result.data, "heic")

        // JPEG 경로(EXIF 6 적용) 결과와 픽셀이 같은 방향인지
        val jpegResult = compressor.compress(privateCopy("cam12.jpg", injectPrivate = false))
        val d = thumbDiff(decode(result.data), decode(jpegResult.data))
        log("heic vs jpeg 썸네일 차이=$d")
        assertTrue("HEIC 결과가 JPEG 결과와 같은 방향이어야 한다(diff=$d)", d < 25.0)

        val uri = mediaStoreCopy("cam12.heic", injectPrivate = false, mime = "image/heic")
        val (r2, ms2, peak2) = measured { compressor.compress(resolver, uri) }
        report("cam12.heic(content Uri) Upload", r2, ms2, peak2)
        assertEquals(result.width, r2.width)
        assertEquals(3000, r2.sourceWidth)
        assertEquals(4000, r2.sourceHeight)
    }

    /**
     * 플랫폼 동작 고정: BitmapFactory 는 HEIF 컨테이너 회전(`irot`)을 픽셀에 반영하지 않고, ExifInterface 는 그 회전을 방향 태그로 준다.
     * SDK 는 이 두 사실에 기대 방향을 한 번만 적용한다. 어느 플랫폼에서 이 시험이 깨지면 이중 회전 여부를 다시 판단해야 한다.
     */
    @Test
    fun platform_heicRotationIsNotAppliedByBitmapFactory() {
        assumeTrue("HEIC 입력 없음", exists("cam12.heic"))
        val file = privateCopy("cam12.heic", injectPrivate = false)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        log("heic bounds=${bounds.outWidth}x${bounds.outHeight} exif orientation=$orientation")
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, orientation)
        assertTrue("BitmapFactory 가 회전하지 않은 가로 크기를 준다", bounds.outWidth > bounds.outHeight)
        // 스트림 경로(사용자 소스·바이트 입력)에서도 ExifInterface 가 같은 방향을 준다
        val viaStream = ExifInterface(ByteArrayInputStream(file.readBytes())).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        assertEquals(orientation, viaStream)
        val r = runBlocking { ImageCompressor { maxDimension = 1024 }.compress(file.readBytes()) }
        assertTrue("바이트 입력도 세로: ${r.width}x${r.height}", r.height > r.width)
        assertTrue(r.warnings.isEmpty())
    }

    // ---------- 5. 보존 묶음: CAMERA 만 켜면 모델은 남고 위치·일련번호는 사라져야 한다 ----------

    @Test
    fun preserveCamera_keepsModelOnly() = runBlocking {
        val file = privateCopy("cam12.jpg", injectPrivate = true)
        val result = ImageCompressor(CompressionProfiles.Upload) {
            preserveMetadata = setOf(MetadataGroup.CAMERA)
        }.compress(file)
        report("cam12.jpg preserve=CAMERA", result, 0, 0)
        val exif = exifOf(result.data)
        assertEquals(ExifInterface(file.path).getAttribute(ExifInterface.TAG_MODEL), exif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID))
        assertNull(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertNull(exif.getAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME))
        assertNull(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        log("preserve=CAMERA 결과 Orientation 원문=${exif.getAttribute(ExifInterface.TAG_ORIENTATION)}")
        assertEquals("보존 묶음을 쓰면 Orientation 을 1 로 명시해야 한다", "1", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
        val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        assertEquals("방향은 픽셀에 반영됐으니 태그가 1 이거나 없어야 한다", 1, o)
        assertTrue(result.sizeInBytes <= 1536 * 1024)
    }

    // ---------- 6. 큰 사진(1억 화소 12000x9000, EXIF 6) — 메모리·시간 ----------

    @Test
    fun hugePhoto_memoryAndTime() = runBlocking {
        assumeTrue("큰 사진 입력 없음", exists("big108.jpg"))
        val file = privateCopy("big108.jpg", injectPrivate = false)
        val upload = ImageCompressor(CompressionProfiles.Upload)
        val baseline = Debug.getNativeHeapAllocatedSize()
        repeat(3) { i ->
            val (r, ms, peak) = measured { upload.compress(file) }
            report("big108.jpg Upload #${i + 1}", r, ms, peak)
            assertTrue(r.height > r.width)
            assertTrue(r.sizeInBytes <= 1536 * 1024)
        }
        // 최대 해상도 요청 — maxPixels(16.7M) 가 막아야 한다
        val maxRes = ImageCompressor { maxDimension = 12000; format = ImageFormat.JPEG; quality = 85 }
        val (r, ms, peak) = measured { maxRes.compress(file) }
        report("big108.jpg maxDimension=12000", r, ms, peak)
        assertTrue("maxPixels 이하: ${r.width}x${r.height}", r.width.toLong() * r.height <= (4096L * 4096L))
        System.gc(); Runtime.getRuntime().gc(); Thread.sleep(300)
        val after = Debug.getNativeHeapAllocatedSize()
        log("native heap baseline=${mb(baseline)} after=${mb(after)} (차이 ${mb(after - baseline)})")
        assertTrue("반복 후 네이티브 힙 누수 의심: +${mb(after - baseline)}", after - baseline < 64L * 1024 * 1024)
    }

    @Test
    fun hugePhoto_cancellation() = runBlocking {
        assumeTrue(exists("big108.jpg"))
        val file = privateCopy("big108.jpg", injectPrivate = false)
        val c = ImageCompressor { maxDimension = 12000; targetBytes = 50_000 }
        val job = async { c.compress(file) }
        delay(150)
        job.cancel()
        try {
            job.await()
            fail("취소돼야 한다")
        } catch (e: CancellationException) {
            log("취소 전파 OK: ${e::class.java.simpleName}")
        }
    }

    // ---------- 7. 다른 프로필 ----------

    @Test
    fun thumbnailAndAvatar() = runBlocking {
        val file = privateCopy("cam12.jpg", injectPrivate = true)
        val t = measured { ImageCompressor(CompressionProfiles.Thumbnail).compress(file) }
        report("cam12.jpg Thumbnail", t.first, t.second, t.third)
        assertTrue(t.first.width <= 512 && t.first.height <= 512 && t.first.sizeInBytes <= 100 * 1024)
        val a = measured { ImageCompressor(CompressionProfiles.Avatar).compress(file) }
        report("cam12.jpg Avatar", a.first, a.second, a.third)
        assertEquals(256, a.first.width)
        assertEquals(256, a.first.height)
        assertNoPrivateMetadata(t.first.data, "thumb")
        assertNoPrivateMetadata(a.first.data, "avatar")
    }

    // ========== 도우미 ==========

    // 입력은 테스트 APK 에 저장소 밖 경로(-PdeviceImageDir)로만 묶인다. 사진을 저장소에 넣지 않는다
    private fun exists(name: String): Boolean = runCatching { instrumentation.context.assets.open(name).close() }.isSuccess

    private fun read(name: String): ByteArray {
        assumeTrue("입력 $name 없음 — -PdeviceImageDir 로 사진 폴더를 지정", exists(name))
        return instrumentation.context.assets.open(name).use { it.readBytes() }
    }

    /** 앱 전용 디렉터리 사본. [injectPrivate] 면 GPS·일련번호·소유자 등 민감 태그를 심는다. */
    private fun privateCopy(name: String, injectPrivate: Boolean, orientation: Int? = null): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, "${System.nanoTime()}_$name")
        f.writeBytes(read(name))
        createdFiles += f
        if (injectPrivate || orientation != null) {
            val exif = ExifInterface(f.path)
            if (injectPrivate) injectSensitive(exif)
            if (orientation != null) exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        return f
    }

    private fun injectSensitive(exif: ExifInterface) {
        exif.setLatLong(37.5665, 126.9780)
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "38/1")
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
        exif.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "TESTSERIAL123")
        exif.setAttribute(ExifInterface.TAG_LENS_SERIAL_NUMBER, "LENSSERIAL9")
        exif.setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, "Test Owner")
        exif.setAttribute(ExifInterface.TAG_ARTIST, "Test Artist")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "secret comment")
    }

    private fun mediaStoreCopy(name: String, injectPrivate: Boolean, mime: String = "image/jpeg"): Uri {
        val bytes = if (injectPrivate) privateCopy(name, true).readBytes() else read(name)
        val uri = newMediaStoreItem("imgsdk_in_${System.nanoTime()}_$name", mime)
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        finishPending(uri)
        return uri
    }

    private fun newMediaStoreItem(displayName: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/imgsdk-test")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        createdUris += uri
        return uri
    }

    private fun finishPending(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun exifOf(data: ByteArray) = ExifInterface(ByteArrayInputStream(data))

    private fun assertNoPrivateMetadata(data: ByteArray, label: String) {
        val exif = exifOf(data)
        val leaked = SENSITIVE_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { "$tag" } }
        log("[$label] 남은 민감 태그=$leaked latLong=${exif.latLong != null}")
        assertNull("$label: GPS 가 남았다", exif.latLong)
        assertTrue("$label: 민감 태그가 남았다 $leaked", leaked.isEmpty())
        val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        assertTrue("$label: 방향 태그가 1 이 아니다($o) — 뷰어가 다시 돌린다", o == 1 || o == 0)
    }

    /** 원본 픽셀(EXIF 무시)에 독립 구현한 방향 변환을 적용한 기대 썸네일이 결과와 가장 가까운지. */
    private fun assertOrientationMatches(original: ByteArray, orientation: Int, result: ByteArray) {
        val raw = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = 16 })
        val actual = decode(result)
        val candidates = listOf(0f, 90f, 180f, 270f).associateWith { deg ->
            val m = Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
        val expectedDeg = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        val diffs = candidates.mapValues { (_, b) ->
            if ((b.width > b.height) != (actual.width > actual.height)) Double.MAX_VALUE else thumbDiff(b, actual)
        }
        log("orientation=$orientation 회전별 차이=${diffs.mapValues { if (it.value == Double.MAX_VALUE) "-" else "%.1f".format(it.value) }}")
        val best = diffs.minByOrNull { it.value }!!.key
        assertEquals("방향 $orientation 은 ${expectedDeg}도 회전이어야 한다", expectedDeg, best)
    }

    private fun decode(data: ByteArray): Bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)

    private fun thumbDiff(a: Bitmap, b: Bitmap): Double {
        val sa = Bitmap.createScaledBitmap(a, 24, 32.coerceAtLeast(1), true)
        val sb = Bitmap.createScaledBitmap(b, 24, 32, true)
        var sum = 0.0
        for (y in 0 until 32) for (x in 0 until 24) {
            sum += abs(gray(sa.getPixel(x, y)) - gray(sb.getPixel(x, y)))
        }
        return sum / (24 * 32)
    }

    private fun gray(c: Int): Double = ((c shr 16 and 0xff) * 0.299 + (c shr 8 and 0xff) * 0.587 + (c and 0xff) * 0.114)

    private suspend fun <T> measured(block: suspend () -> T): Triple<T, Long, Long> {
        System.gc(); Runtime.getRuntime().gc()
        val base = Debug.getNativeHeapAllocatedSize() + usedJava()
        val peak = AtomicLong(base)
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val now = Debug.getNativeHeapAllocatedSize() + usedJava()
                if (now > peak.get()) peak.set(now)
                Thread.sleep(2)
            }
        }.apply { start() }
        val start = System.nanoTime()
        try {
            val r = block()
            return Triple(r, (System.nanoTime() - start) / 1_000_000, peak.get() - base)
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    private fun usedJava(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun report(label: String, r: CompressedImage, ms: Long, peak: Long) {
        log("ROW|$label|${r.format}|${r.sourceWidth}x${r.sourceHeight}|${r.width}x${r.height}|${r.sizeInBytes}|q=${r.quality}|ss=${r.sampleSize}|att=${r.attempts}|${ms}ms|peak+${mb(peak)}")
    }

    private fun mb(b: Long) = "%.1fMB".format(b / 1024.0 / 1024.0)

    private fun log(msg: String) { Log.i(TAG, msg) }

    private companion object {
        const val TAG = "IMGDEV"
        val SENSITIVE_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_ARTIST, ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_MAKER_NOTE,
        )
    }
}
