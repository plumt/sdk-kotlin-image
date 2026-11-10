plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover) apply false
    // 공개 API 를 모듈별 .api 파일로 고정한다. 호환이 깨지는 변경은 apiCheck 가 막는다
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // 발행하지 않는 확인용 앱 — release(R8) 빌드로 consumer-rules.pro 만으로 충분한지 본다
    ignoredProjects.add("sample")
}
