package net.plumt.image.compress

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 개인정보 기능의 핵심 시험 — 위치와 그 밖의 메타데이터가 기본으로 제거되는지, 선택한 묶음만 남는지. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetadataPrivacyTest {
    private val marker = "MARKER-9137"

    private val tagged: ByteArray = TestImages.withExif(TestImages.noisyJpeg(200, 150)) {
        setLatLong(37.5665, 126.9780)
        setAltitude(38.0)
        setAttribute(ExifInterface.TAG_MAKE, "AcmeCam")
        setAttribute(ExifInterface.TAG_MODEL, "X-100")
        setAttribute(ExifInterface.TAG_SOFTWARE, "FirmwareZ")
        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:02 03:04:05")
        setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
        setAttribute(ExifInterface.TAG_ARTIST, marker)
        setAttribute(ExifInterface.TAG_COPYRIGHT, "(c) tester")
        setAttribute(ExifInterface.TAG_USER_COMMENT, marker)
        setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SER-12345")
        setAttribute(ExifInterface.TAG_ORIENTATION, "1")
    }

    private fun exif(b: ByteArray) = ExifInterface(ByteArrayInputStream(b))
    private fun contains(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)
    private fun compress(block: ImageCompressorBuilder.() -> Unit) = runBlocking { ImageCompressor(block = block).compress(tagged) }

    @Test fun 시험_입력에는_실제로_메타데이터가_들어있다() {
        val e = exif(tagged)
        assertNotNull(e.latLong)
        assertEquals("AcmeCam", e.getAttribute(ExifInterface.TAG_MAKE))
        assertTrue(contains(tagged, marker))
    }

    @Test fun 기본값은_위치를_포함한_모든_메타데이터를_제거한다() {
        val r = compress { maxDimension = 100 }
        val e = exif(r.data)
        assertNull(e.latLong)
        assertFalse(e.hasAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertFalse(e.hasAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertFalse(e.hasAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        assertNull(e.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(e.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(e.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(e.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertNull(e.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertFalse(contains(r.data, marker))
        assertFalse(contains(r.data, "AcmeCam"))
        assertFalse(contains(r.data, "Exif"))
        assertTrue(r.preservedMetadata.isEmpty())
    }

    @Test fun 크기를_줄일_필요가_없어도_원본을_그대로_돌려주지_않아_위치가_제거된다() {
        val r = compress { maxDimension = 5000 }
        assertEquals(1, r.sampleSize)
        assertNull(exif(r.data).latLong)
        assertFalse(contains(r.data, marker))
    }

    @Test fun 모든_형식에서_제거된다() {
        for (f in ImageFormat.values()) {
            val r = compress { format = f }
            assertFalse("$f 에 문구 남음", contains(r.data, marker))
            assertFalse("$f 에 GPS 남음", contains(r.data, "AcmeCam"))
            assertNull("$f", runCatching { exif(r.data).latLong }.getOrNull())
        }
    }

    @Test fun 용량_목표_모드에서도_제거된다() {
        val r = compress { targetBytes = 8000 }
        assertNull(exif(r.data).latLong)
        assertFalse(contains(r.data, marker))
    }

    @Test fun 위치만_보존하면_위치만_남는다() {
        val r = compress { preserveMetadata = setOf(MetadataGroup.LOCATION) }
        val e = exif(r.data)
        val ll = e.latLong!!
        assertEquals(37.5665, ll[0], 0.001)
        assertEquals(126.9780, ll[1], 0.001)
        assertNull(e.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(e.getAttribute(ExifInterface.TAG_ARTIST))
        assertFalse(contains(r.data, marker))
        assertEquals(setOf(MetadataGroup.LOCATION), r.preservedMetadata)
    }

    @Test fun 카메라와_촬영시각과_노출_묶음() {
        val r = compress { preserveMetadata = setOf(MetadataGroup.CAMERA, MetadataGroup.CAPTURE_TIME, MetadataGroup.EXPOSURE) }
        val e = exif(r.data)
        assertEquals("AcmeCam", e.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("X-100", e.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("FirmwareZ", e.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("2026:01:02 03:04:05", e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(2.8, e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 0.01)
        assertNull(e.latLong)
        assertNull(e.getAttribute(ExifInterface.TAG_ARTIST))
    }

    @Test fun 저작권_묶음은_작성자와_저작권만() {
        val r = compress { preserveMetadata = setOf(MetadataGroup.COPYRIGHT) }
        val e = exif(r.data)
        assertEquals("(c) tester", e.getAttribute(ExifInterface.TAG_COPYRIGHT))
        assertEquals(marker, e.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(e.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertNull(e.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test fun 어떤_묶음을_켜도_일련번호_사용자코멘트_방향은_복사하지_않는다() {
        val r = compress { preserveMetadata = MetadataGroup.values().toSet() }
        val e = exif(r.data)
        assertNull(e.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertNull(e.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertFalse(e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 2..8)
        assertFalse(contains(r.data, "SER-12345"))
        assertNotNull(e.latLong)
    }

    @Test fun 원본에_없는_묶음을_켜도_실패하지_않고_결과는_제거_상태와_같다() {
        val plain = TestImages.noisyJpeg(100, 100)
        val r = runBlocking { ImageCompressor { preserveMetadata = setOf(MetadataGroup.LOCATION) }.compress(plain) }
        assertNull(exif(r.data).latLong)
        assertTrue(r.preservedMetadata.isEmpty())
        val stripped = runBlocking { ImageCompressor().compress(plain) }
        assertArrayEquals(stripped.data, r.data)
    }

    @Test fun 용량_목표와_보존을_함께_쓰면_붙인_뒤_크기로_맞춘다() {
        val target = 9000L
        val r = compress { preserveMetadata = setOf(MetadataGroup.LOCATION, MetadataGroup.CAMERA); targetBytes = target }
        assertTrue("${r.sizeInBytes}", r.sizeInBytes <= target)
        assertNotNull(exif(r.data).latLong)
    }

    @Test fun 임시_파일을_남기지_않는다() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir")!!)
        val before = dir.listFiles { f -> f.name.startsWith("imgc") }?.size ?: 0
        compress { preserveMetadata = setOf(MetadataGroup.LOCATION) }
        val after = dir.listFiles { f -> f.name.startsWith("imgc") }?.size ?: 0
        assertEquals(before, after)
    }
}
