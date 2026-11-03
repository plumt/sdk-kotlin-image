plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover) apply false
    // 공개 API 를 모듈별 .api 파일로 고정한다. 호환이 깨지는 변경은 apiCheck 가 막는다
    alias(libs.plugins.binary.compatibility.validator)
}
