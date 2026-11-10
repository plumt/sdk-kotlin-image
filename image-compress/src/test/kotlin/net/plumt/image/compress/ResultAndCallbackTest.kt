package net.plumt.image.compress

import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** 결과 타입 형태(suspend·콜백)는 compress 와 같은 결과를 내고, 예상 실패만 결과로 바꾼다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultAndCallbackTest {
    private val photo = TestImages.noisyJpeg(320, 240)

    @Test fun 성공_결과는_같은_입력이면_같다() {
        val compressor = ImageCompressor.create(CompressorConfig.builder().maxDimension(160).targetBytes(8_000).build())
        val thrown = runBlocking { compressor.compress(ImageSource.bytes(photo)) }.orThrow()
        val result = runBlocking { compressor.compress(ImageSource.bytes(photo)) }
        assertTrue(result is CompressionResult.Success)
        assertEquals(thrown, (result as CompressionResult.Success).image)
        assertTrue(compressor.toString().startsWith("ImageCompressor(CompressorConfig("))
        assertEquals(CompressionResult.Success(thrown), result)
        assertEquals(CompressionResult.Success(thrown).hashCode(), result.hashCode())
        assertTrue(result.toString().startsWith("Success(CompressedImage("))
    }

    @Test fun 결과_디코딩_실패는_결과() {
        val result = runBlocking { ImageCompressor().compress(ImageSource.bytes(byteArrayOf(1, 2, 3))) }
        val failure = result as CompressionResult.Failure
        assertTrue(failure.error is ImageDecodeException)
        assertTrue(failure.toString().startsWith("Failure(ImageDecodeException"))
        assertEquals(failure, CompressionResult.Failure(failure.error))
        assertEquals(failure.hashCode(), CompressionResult.Failure(failure.error).hashCode())
    }

    @Test fun 결과_용량_목표_실패는_결과() {
        val result = runBlocking { ImageCompressor { targetBytes = 10; minDimension = 200 }.compress(ImageSource.bytes(photo)) }
        val error = (result as CompressionResult.Failure).error as TargetSizeNotReachedException
        assertEquals(10L, error.targetBytes)
    }

    @Test fun 결과_입출력_실패는_결과() {
        val missing = ImageSource.file(java.io.File("/nonexistent/none.jpg"))
        val result = runBlocking { ImageCompressor().compress(missing) }
        assertTrue((result as CompressionResult.Failure).error is ImageIoException)
    }

    @Test fun 결과_프로그래머_오류는_던진다() {
        val bad = ImageCompressor { resizer = Resizer { w, h -> ResizePlan(1, 1, w, h, 10, 10) } }
        try {
            runBlocking { bad.compress(ImageSource.bytes(photo)) }
            fail("IAE 를 던져야 한다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("밖의 영역"))
        }
    }

    @Test fun 콜백은_메인_스레드에서_한_번() {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val compressor = ImageCompressor { maxDimension = 100; dispatcher = worker }
            val received = ArrayList<CompressionResult>()
            var thread: Thread? = null
            val handle = compressor.compress(ImageSource.bytes(photo)) {
                thread = Thread.currentThread()
                received += it
            }
            awaitMain { received.isNotEmpty() }
            assertEquals(1, received.size)
            assertSame(Looper.getMainLooper().thread, thread)
            assertEquals(100, (received[0] as CompressionResult.Success).image.width)
            assertFalse(handle.isActive)
            assertEquals("CompressionCallHandle(active=false)", handle.toString())
        } finally {
            worker.close()
        }
    }

    @Test fun 콜백_실패도_결과() {
        val received = ArrayList<CompressionResult>()
        ImageCompressor().compress(ImageSource.bytes(byteArrayOf(9))) { received += it }
        awaitMain { received.isNotEmpty() }
        assertTrue((received.single() as CompressionResult.Failure).error is ImageDecodeException)
    }

    @Test fun 취소하면_콜백을_부르지_않는다() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : ImageSource {
            override fun open(): java.io.InputStream {
                gate.await()
                return photo.inputStream()
            }
        }
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            var called = false
            val handle = ImageCompressor { dispatcher = worker }.compress(slow) { called = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(handle.isActive)
            handle.cancel()
            gate.countDown()
            handle.cancel()
            repeat(20) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(called)
            assertFalse(handle.isActive)
        } finally {
            worker.close()
        }
    }

    @Test fun 대상에_쓴다_콜백과_suspend() {
        val out = java.io.ByteArrayOutputStream()
        val r = runBlocking { ImageCompressor.create().compress(ImageSource.bytes(photo), ImageTarget.stream(out)) }
        assertEquals((r as CompressionResult.Success).image.data.toList(), out.toByteArray().toList())
        val got = ArrayList<CompressionResult>()
        val file = java.io.File.createTempFile("cbk", ".jpg").also { it.delete() }
        ImageCompressor.create().compress(ImageSource.bytes(photo), ImageTarget.file(file)) { got += it }
        awaitMain { got.isNotEmpty() }
        assertTrue(got.single() is CompressionResult.Success)
        assertTrue(file.length() > 0)
        file.delete()
    }

    @Test fun 쓰기_실패는_입출력_결과() {
        val failing = object : ImageTarget {
            override fun write(data: ByteArray) = throw java.io.IOException("full")
        }
        val r = runBlocking { ImageCompressor.create().compress(ImageSource.bytes(photo), failing) }
        assertTrue((r as CompressionResult.Failure).error is ImageIoException)
        val crashing = object : ImageTarget {
            override fun write(data: ByteArray) = throw IllegalStateException("bug")
        }
        val c = runBlocking { ImageCompressor.create().compress(ImageSource.bytes(photo), crashing) }
        assertTrue((c as CompressionResult.Failure).error.cause is IllegalStateException)
        val denied = object : ImageTarget {
            override fun write(data: ByteArray) = throw SecurityException("no")
        }
        val d = runBlocking { ImageCompressor.create().compress(ImageSource.bytes(photo), denied) }
        assertTrue((d as CompressionResult.Failure).error.cause is SecurityException)
    }

    @Test fun 직접_만든_대상의_IAE_는_던진다() {
        val bad = object : ImageTarget {
            override fun write(data: ByteArray) = throw IllegalArgumentException("mine")
        }
        try {
            runBlocking { ImageCompressor.create().compress(ImageSource.bytes(photo), bad) }
            fail("IAE 를 던져야 한다")
        } catch (e: IllegalArgumentException) {
            assertEquals("mine", e.message)
        }
    }

    @Test fun 결과_값_비교() {
        val a = CompressionResult.Failure(ImageEncodeException("x"))
        assertNotEquals(a, CompressionResult.Failure(ImageEncodeException("x")))
        assertFalse(a.equals("x"))
        assertFalse(CompressionResult.Success(FakeImageCompressorTestImage.one()).equals(a))
        assertNull((a as CompressionResult).let { (it as? CompressionResult.Success)?.image })
    }

    private object FakeImageCompressorTestImage {
        fun one(): CompressedImage = runBlocking { FakeImageCompressor().compress(ImageSource.bytes(byteArrayOf(1))) }.orThrow()
    }

    private fun awaitMain(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("시간 안에 콜백이 오지 않았다", done())
    }
}
