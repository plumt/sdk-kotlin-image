package net.plumt.image.compress

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking

/** 형식·API 레벨 분기. 최소(23)·API 30 경계 직전(29)·경계(30)·최신(34)에서 같은 코드를 돌린다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormatAndApiTest {
    @Test fun 매핑_API_29_는_구형_WEBP() {
        assertEquals(Bitmap.CompressFormat.WEBP, compressFormatFor(ImageFormat.WEBP, 29))
        assertEquals(Bitmap.CompressFormat.WEBP, compressFormatFor(ImageFormat.WEBP_LOSSLESS, 29))
        assertEquals(Bitmap.CompressFormat.WEBP, compressFormatFor(ImageFormat.WEBP, 23))
    }

    @Test fun 매핑_API_30_부터_손실_무손실_상수() {
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, compressFormatFor(ImageFormat.WEBP, 30))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSLESS, compressFormatFor(ImageFormat.WEBP_LOSSLESS, 30))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSLESS, compressFormatFor(ImageFormat.WEBP_LOSSLESS, 36))
    }

    @Test fun 매핑_JPEG_PNG_는_API와_무관() {
        for (api in listOf(23, 29, 30, 36)) {
            assertEquals(Bitmap.CompressFormat.JPEG, compressFormatFor(ImageFormat.JPEG, api))
            assertEquals(Bitmap.CompressFormat.PNG, compressFormatFor(ImageFormat.PNG, api))
        }
    }

    private fun roundTrip(): List<Int> = ImageFormat.values().map { f ->
        val r = runBlocking { ImageCompressor { format = f; maxDimension = 64 }.compress(TestImages.noisyJpeg(128, 96)) }
        assertEquals(64, r.width)
        assertEquals(f, r.format)
        r.data.size
    }

    @Config(sdk = [23]) @Test fun API_23_모든_형식() { assertTrue(roundTrip().all { it > 0 }) }
    @Config(sdk = [29]) @Test fun API_29_모든_형식() { assertTrue(roundTrip().all { it > 0 }) }
    @Config(sdk = [30]) @Test fun API_30_모든_형식() { assertTrue(roundTrip().all { it > 0 }) }
    @Config(sdk = [34]) @Test fun API_34_모든_형식() { assertTrue(roundTrip().all { it > 0 }) }
    @Config(sdk = [34]) @Test fun 손실_WebP_는_품질이_용량에_반영된다() {
        val src = TestImages.noisyJpeg(200, 200)
        val hi = runBlocking { ImageCompressor { format = ImageFormat.WEBP; quality = 95 }.compress(src) }
        val lo = runBlocking { ImageCompressor { format = ImageFormat.WEBP; quality = 20 }.compress(src) }
        assertTrue(lo.sizeInBytes < hi.sizeInBytes)
    }

    @Config(sdk = [34]) @Test fun 위치_보존은_JPEG_에서_동작() {
        val src = TestImages.withExif(TestImages.noisyJpeg(100, 100)) { setLatLong(37.5, 127.0) }
        val r = runBlocking { ImageCompressor { preserveMetadata = setOf(MetadataGroup.LOCATION) }.compress(src) }
        assertNotNull(ExifInterface(ByteArrayInputStream(r.data)).latLong)
        assertNull(null)
    }
}
