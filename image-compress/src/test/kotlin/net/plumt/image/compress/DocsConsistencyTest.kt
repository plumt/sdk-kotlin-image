package net.plumt.image.compress

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 값을 하나 더할 때 고치는 곳은 정의 한 곳이다. 그 정의에서 파생되는 문서(ERRORS.md·README·CHANGELOG)가
 * 어긋나면 여기서 실패한다.
 */
class DocsConsistencyTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val errors = File(root, "ERRORS.md").readText()
    private val readme = File(root, "README.md").readText()
    private val changelog = File(root, "CHANGELOG.md").readText()
    private val version = File(root, "image-compress/gradle.properties").readLines()
        .first { it.startsWith("VERSION_NAME=") }.substringAfter('=')

    /** 실패 하위 타입마다 이름. `else` 가 없어 하위 타입을 더하면 여기서 컴파일이 깨진다. */
    private fun name(e: ImageCompressionException): String = when (e) {
        is ImageDecodeException -> "ImageDecodeException"
        is ImageEncodeException -> "ImageEncodeException"
        is ImageIoException -> "ImageIoException"
        is ImageOutOfMemoryException -> "ImageOutOfMemoryException"
        is TargetSizeNotReachedException -> "TargetSizeNotReachedException"
    }

    private val failures = listOf(
        ImageDecodeException("d"),
        ImageEncodeException("e"),
        ImageIoException("i"),
        ImageOutOfMemoryException("o"),
        TargetSizeNotReachedException(1, 1, 1, 1),
    )

    @Test fun `ERRORS 는 실패 하위 타입을 표 행으로 모두 적는다`() {
        val missing = failures.map(::name).filterNot { errors.contains("| `$it` |") }
        assertTrue("ERRORS.md 표에 없는 실패: $missing", missing.isEmpty())
    }

    @Test fun `ERRORS 는 결과 타입과 경고 값을 적는다`() {
        val missing = listOf("CompressionResult.Failure", "CompressionResult.Success").filterNot { errors.contains(it) } +
            CompressionWarning.values.map { it.name }.filterNot { errors.contains(it) }
        assertTrue("ERRORS.md 에 없는 값: $missing", missing.isEmpty())
    }

    @Test fun `README 는 출력 형식과 메타데이터 묶음을 모두 적는다`() {
        val missing = ImageFormat.values.map { "`${it.name}`" }.filterNot { readme.contains(it) } +
            MetadataGroup.values.map { "`${it.name}`" }.filterNot { readme.contains(it) }
        assertTrue("README 에 없는 값: $missing", missing.isEmpty())
    }

    @Test fun `README 설치 줄과 빌드 버전이 같다`() {
        val line = "net.plumt.image:image-compress:$version"
        assertTrue("README 에 설치 줄이 없다: $line", readme.contains(line))
    }

    @Test fun `README 는 API 레벨 표와 알려진 한계를 둔다`() {
        assertTrue(readme.contains("## API 레벨별 동작"))
        assertTrue(readme.contains("## 알려진 한계"))
        for (api in listOf(23, 26, 28, 29, 30, 31, 34)) assertTrue("API 표에 $api 행이 없다", readme.contains("| $api"))
    }

    @Test fun `README 는 옮기는 표를 두고 CHANGELOG 현재 버전은 깨짐을 적는다`() {
        assertTrue(readme.contains("## 0.0.x 에서 옮기기"))
        val section = changelog.substringAfter("### [$version]").substringBefore("\n### [")
        assertTrue("CHANGELOG $version 에 깨짐이 없다", section.contains("#### 깨짐"))
    }

    @Test fun `ERRORS 는 상수 값을 모두 적고 README 는 상수 클래스 when 의 else 를 안내한다`() {
        assertTrue(readme.contains("`else`"))
        val missing = CompressionWarning.values.map { it.name }.filterNot { readme.contains(it) }
        assertTrue("README 에 없는 경고: $missing", missing.isEmpty())
    }

    @Test fun `CHANGELOG 는 현재 버전을 적고 제목은 정한 네 가지만 쓴다`() {
        assertTrue("CHANGELOG 에 $version 이 없다", changelog.contains("### [$version]"))
        val headings = changelog.lines().filter { it.startsWith("#### ") }.map { it.removePrefix("#### ").trim() }.toSet()
        val unknown = headings - setOf("추가", "바뀜", "깨짐", "고침")
        assertTrue("CHANGELOG 에 정하지 않은 제목: $unknown", unknown.isEmpty())
    }
}
