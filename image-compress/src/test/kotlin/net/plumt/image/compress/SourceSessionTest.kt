package net.plumt.image.compress

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** 입력을 한 번 열어 되감아 쓰는 세션, 메모리 조각 버퍼, 플랫폼 품질 매핑. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private val jpeg = TestImages.noisyJpeg(320, 240)

    @Before fun registerProvider() {
        Robolectric.setupContentProvider(FileBackedProvider::class.java, FileBackedProvider.AUTHORITY)
    }

    // ---------- 세션 ----------

    @Test fun 파일_세션은_되감아_같은_내용을_여러_번_준다() {
        val f = tmp.newFile("a.bin").apply { writeBytes(Random(1).nextBytes(100_000)) }
        val session = ImageSource.file(f).openSession()
        assertTrue(session is SeekableFdSession)
        session.use {
            repeat(3) { _ ->
                val s = it.stream()
                s.read(ByteArray(1234)) // 일부만 읽고 닫아도
                s.close()
                assertArrayEquals(f.readBytes(), it.stream().use { x -> x.readBytes() })
            }
        }
        // 공개 open() 도 그대로 동작한다
        assertArrayEquals(f.readBytes(), ImageSource.file(f).open().use { it.readBytes() })
    }

    @Test fun 사용자_소스는_매번_다시_연다() {
        var opens = 0
        val source = object : ImageSource {
            override fun open() = ByteArrayInputStream(jpeg).also { opens++ }
        }
        assertTrue(source.openSession() is ReopeningSession)
        runBlocking { ImageCompressor { maxDimension = 100 }.compress(source) }
        assertEquals(3, opens) // 크기 · EXIF · 디코딩. 0.0.1 은 메타데이터 보존 시 4회
    }

    @Test fun 파일_입력도_한_번만_연다() {
        val f = tmp.newFile("in.jpg").apply { writeBytes(jpeg) }
        val r = runBlocking { ImageCompressor { maxDimension = 100 }.compress(f) }
        assertEquals(100, r.width)
    }

    @Test fun 기술자를_못_주는_provider_는_스트림을_다시_여는_방식으로_돌아간다() {
        FileBackedProvider.reset(tmp.newFile("in.jpg").apply { writeBytes(jpeg) })
        FileBackedProvider.partialAsset = true
        assertTrue(ImageSource.uri(resolver, FileBackedProvider.uri).openSession() is ReopeningSession)
        FileBackedProvider.modes.clear()
        val r = runBlocking { ImageCompressor { maxDimension = 100 }.compress(resolver, FileBackedProvider.uri) }
        assertEquals(100, r.width)
        assertEquals(4, FileBackedProvider.modes.size) // 기술자 시도 1 + 크기·EXIF·디코딩 3
    }

    // ---------- 메모리 조각 버퍼 ----------

    @Test fun 조각_버퍼는_모든_읽기_방식에서_원본과_같다() {
        val random = Random(99)
        for (size in listOf(0, 1, 6, 7, 8, 13, 14, 100, 1000)) {
            val data = random.nextBytes(size)
            val buf = ChunkedBytes.readFrom(ByteArrayInputStream(data), Long.MAX_VALUE, chunkSize = 7)
            assertEquals(size.toLong(), buf.size)
            assertTrue("capacity=${buf.capacity}", buf.capacity - buf.size < 7)
            // 한 바이트씩
            buf.openStream().use { s -> assertArrayEquals(data, ByteArray(size) { s.read().toByte() }); assertEquals(-1, s.read()) }
            // 무작위 길이 배열 읽기 + 건너뛰기 + mark/reset
            buf.openStream().use { s ->
                assertTrue(s.markSupported())
                val out = java.io.ByteArrayOutputStream()
                var pos = 0
                while (true) {
                    if (random.nextInt(5) == 0) {
                        s.mark(0)
                        val peek = s.read()
                        s.reset()
                        assertEquals(if (pos < size) data[pos].toInt() and 0xFF else -1, peek)
                    }
                    val arr = ByteArray(random.nextInt(1, 20))
                    val off = random.nextInt(0, arr.size)
                    val n = s.read(arr, off, arr.size - off)
                    if (n < 0) break
                    out.write(arr, off, n)
                    pos += n
                    assertEquals(size - pos, s.available())
                }
                assertArrayEquals(data, out.toByteArray())
                assertEquals(0, s.read(ByteArray(3), 0, 0))
            }
            buf.openStream().use { s ->
                assertEquals(minOf(5L, size.toLong()), s.skip(5))
                assertEquals(0L, s.skip(-1))
                if (size > 5) assertEquals(data[5].toInt() and 0xFF, s.read())
                assertThrows(IndexOutOfBoundsException::class.java) { s.read(ByteArray(2), 1, 5) }
            }
        }
    }

    @Test fun 조각_버퍼는_한도를_넘으면_IOException() {
        assertThrows(IOException::class.java) { ChunkedBytes.readFrom(ByteArrayInputStream(ByteArray(20)), 19, chunkSize = 7) }
        assertEquals(20L, ChunkedBytes.readFrom(ByteArrayInputStream(ByteArray(20)), 20, chunkSize = 7).size)
    }

    @Test fun 스트림_입력은_기본_조각_크기로_읽어도_압축된다() {
        val big = TestImages.noisyJpeg(900, 700)
        assertTrue(big.size > ChunkedBytes.CHUNK_SIZE)
        val r = runBlocking { ImageCompressor { maxDimension = 300 }.compress(ByteArrayInputStream(big)) }
        assertEquals(300, r.width)
        assertNotNull(r.data)
    }

    // ---------- 플랫폼 품질 ----------

    @Test fun 플랫폼_품질_매핑() {
        assertEquals(99, platformQuality(ImageFormat.WEBP, 100, 29)) // API 29 구형 WEBP 의 100 은 무손실
        assertEquals(100, platformQuality(ImageFormat.WEBP, 100, 28))
        assertEquals(100, platformQuality(ImageFormat.WEBP, 100, 30))
        assertEquals(80, platformQuality(ImageFormat.WEBP, 80, 29))
        for (sdk in listOf(23, 29, 30, 36)) {
            assertEquals(100, platformQuality(ImageFormat.WEBP_LOSSLESS, 1, sdk))
            assertEquals(42, platformQuality(ImageFormat.JPEG, 42, sdk))
            assertEquals(42, platformQuality(ImageFormat.PNG, 42, sdk))
        }
    }
}
