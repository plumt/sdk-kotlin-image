# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## image-compress

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `ImageCompressor` 와 DSL `ImageCompressor { ... }`, 프로필(`CompressionProfile`, 기본 Thumbnail·Upload·Avatar)
- 입력 `ByteArray`·`File`·`Uri`·`InputStream`(`ImageSource` 로 확장), 출력 바이트·`File`·`Uri`·`OutputStream`
- JPEG·PNG·WebP(손실/무손실, API 30 미만 대체 포함) 출력, HEIC·AVIF 입력(플랫폼 지원 시)
- 축소 디코딩(`inSampleSize`), `maxPixels` 제한, EXIF 방향 8종 적용, 투명 픽셀 합성(JPEG)
- 용량 목표: 품질 이분 탐색 후 크기 단계 축소, 결과 보고(품질·크기·시도 횟수)
- 메타데이터(위치 포함)는 기본 제거, `MetadataGroup` 으로 선택 보존
- 확장 지점 `Resizer`·`Encoder`·`CompressionStrategy`·`ImageSource`
- 오류: `ImageCompressionException` 계열 5종, 잘못된 설정은 `IllegalArgumentException`
