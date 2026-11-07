plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.image.compress"
    compileSdk = 36

    defaultConfig {
        // 23 미만 기기는 androidx ExifInterface 최소 요구(21)를 만족하지만, 이 SDK 는 23 을 하한으로 시험한다
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        // 실기기 계측 테스트(connectedDebugAndroidTest). CI 는 기기가 없어 돌리지 않는다
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 실기기 테스트 입력 사진 폴더(저장소 밖). 지정하지 않으면 계측 테스트는 건너뛴다
    providers.gradleProperty("deviceImageDir").orNull?.let { dir ->
        sourceSets.getByName("androidTest").assets.srcDir(dir)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 코루틴 디버그 스택 복구가 예외를 복사해 cause 가 달라지는 것을 막아 cause 보존을 정확히 검증한다
            it.systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // suspend·CoroutineDispatcher 가 공개 API 에 나오므로 api 로 노출한다
    api(libs.kotlinx.coroutines.core)
    // 방향 읽기·메타데이터 선별 보존. 공개 API 에 androidx 타입을 드러내지 않는다
    implementation(libs.androidx.exifinterface)
    // @RequiresApi 만 쓴다. exifinterface 가 이미 런타임으로 끌어오는 것과 같은 버전이라 의존이 늘지 않는다
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.exifinterface)
}

mavenPublishing {
    coordinates(artifactId = "image-compress", version = property("VERSION_NAME").toString())
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("image-compress")
        description.set("안드로이드 이미지 축소 디코딩·압축·용량 목표 맞춤·위치정보 제거를 하는 Kotlin 라이브러리")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        verify {
            rule {
                // 라인 커버리지 90% 미만이면 build 가 실패한다
                minBound(90)
            }
        }
    }
}
