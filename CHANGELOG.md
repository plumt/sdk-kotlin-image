# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## image-compress

### [Unreleased]

### [0.1.0] - 2026-11-11

좌표는 그대로(`net.plumt.image:image-compress`)이고 공개 API 가 바뀐다(1.0 전 마이너 상승). 옛 심볼은 `@Deprecated` 로 남기지 않았다. 옮기는 표는 README "0.0.x 에서 옮기기".

#### 추가
- 진입점 `ImageCompressor.create()`·`ImageCompressor.create(config)` 와 불변 설정 `CompressorConfig.builder()`·`toBuilder()`
- 결과 타입 `CompressionResult`(`Success.image`·`Failure.error`) — 예상되는 실패를 던지지 않고 돌려준다
- 콜백 형태 `compress(source, callback)`·`compress(source, target, callback)` — 결과는 메인 스레드에서 한 번, `CompressionCallHandle` 로 취소
- 출력 대상 `ImageTarget`(`file`·`uri`·`stream`, 직접 구현 가능)과 `compress(source, target)`
- `FakeImageCompressor` — 앱 단위 테스트용 가짜 압축기(기기·Robolectric 없이 돈다)
- `ImageFormat.supportsAlpha`, `ImageFormat.values`·`MetadataGroup.values`·`CompressionWarning.values`
- README "API 레벨별 동작" 표·"알려진 한계"·"0.0.x 에서 옮기기", R8 release 확인용 `sample` 앱(발행 안 함)
- 런타임 의존 `kotlinx-coroutines-android`(콜백을 메인 스레드로 주는 데만 쓴다)

#### 바뀜
- 공개 인터페이스의 기본 구현을 Java 기본 메서드로 낸다(`jvmDefault = NO_COMPATIBILITY`, `DefaultImpls` 없음)

#### 깨짐
- `ImageCompressor { ... }`·`ImageCompressor(profile) { ... }` DSL 과 `ImageCompressorBuilder` 를 지웠다 → `ImageCompressor.create(CompressorConfig.builder()...build())`. 프로필은 `builder().profile(p)`
- `CompressionProfile` 의 설정 블록이 `CompressorConfig.Builder` 를 받는다 → `{ maxWidth = 1200 }` 을 `{ maxWidth(1200) }` 으로
- `compress(source)` 가 `CompressedImage` 대신 `CompressionResult` 를 돌려주고 `ImageCompressionException` 을 던지지 않는다 → `when` 으로 `Success.image`·`Failure.error` 를 가린다. `IllegalArgumentException`(프로그래머 오류)과 취소는 전처럼 던진다
- 확장 함수 `compress(ByteArray/File/(resolver, uri)/InputStream)`·`compressToFile`·`compressToUri` 를 지웠다 → `compress(ImageSource.xxx(...))`, `compress(source, ImageTarget.file/uri(...))`
- `CompressedImage.writeTo(...)` 3종을 지웠다 → 압축할 때 `ImageTarget` 을 넘기거나 `image.data` 를 직접 쓴다
- `ImageFormat`·`MetadataGroup`·`CompressionWarning` 이 `enum` 에서 상수 클래스가 됐다 → `values()` 는 `values`, `when` 에는 `else`, `ordinal`·`valueOf`·`entries` 없음. `Encoder.encode` 의 `format` 인자도 이 클래스다
- 기본값 인자를 지웠다: `FitResizer`·`CenterSquareResizer`·`TargetSizeStrategy` 는 짧은 생성자 오버로드, `ImageSource.stream(input)`·`stream(input, maxBytes)`, `ImageEncodeException(message)`·`(message, cause)`. 일부 인자만 이름 붙여 넘기던 `TargetSizeStrategy(bytes, minQuality = 10)` 은 네 인자를 모두 준다
- `CompressorConfig` 생성은 빌더로만 한다. `CompressedImage.warnings` 의 기본값이 없어졌다(생성자는 내부 전용이라 앱 영향 없음)

### [0.0.2] - 2026-11-07

공개 API 는 0.0.1 과 바이너리 호환이다(추가만). 다만 동작이 바뀐 것이 있어 아래 "깨짐" 에 소급해 적는다.

#### 추가
- `CompressedImage.warnings` 와 `CompressionWarning.EXIF_UNREADABLE` — 손상된 EXIF 를 대체 처리했음을 결과에 표시
- 실기기 계측 시험(`src/androidTest`, `-PdeviceImageDir` 로 사진 폴더 지정). CI 는 돌리지 않는다

#### 바뀜
- 용량 목표 탐색: 줄인 크기에서는 최소 품질부터 시도하고, 용량이 픽셀 수에 비례한다고 보고 다음 크기를 추정해 건너뛴다. 도달 불가능한 목표의 인코딩 횟수가 수십 회에서 몇 회로 준다. 같은 입력에서 0.0.1 과 결과 크기·품질이 다를 수 있다
- `targetBytes` 와 `strategy` 를 함께 주면 IAE 이던 것을, 나중에 대입한 쪽이 쓰이게 했다(용량 목표가 있는 프로필 위에 `strategy` 를 덮어쓸 수 있다)
- `writeTo(file)` 는 임시 파일에 쓴 뒤 이름을 바꾼다. 실패해도 기존 파일을 지우지 않는다
- 문서: 발행 상태, WebP 품질의 실제 동작, 색 프로필·게인맵 처리, HEIC 메타데이터 보존 제약, 동시 실행 권고, `centerSquare` 의 확대 기본값

#### 깨짐
- 회전된 사진(EXIF 방향 5~8)의 `sourceWidth`·`sourceHeight` 가 방향을 반영한 값으로 바뀌었다(폭과 높이가 서로 바뀐다)
- 읽기 경로의 `SecurityException` 이 `ImageEncodeException` 대신 `ImageIoException` 으로 나간다
- 플랫폼 내부의 `IllegalArgumentException` 이 그대로 나가지 않고 `ImageCompressionException`(`ImageEncodeException`)으로 감싸진다
- `targetBytes` 와 `strategy` 를 함께 주면 `IllegalArgumentException` 대신 나중에 대입한 쪽이 쓰인다
- 용량 목표의 결과 크기·품질이 같은 입력에서 0.0.1 과 다를 수 있다

#### 고침
- HEIC·AVIF 세로 사진이 누워 나오던 문제. 플랫폼 디코더가 컨테이너 회전을 적용한다는 가정을 버리고 EXIF 방향을 형식과 관계없이 직접 적용한다. `sourceWidth`·`sourceHeight` 도 방향을 반영한다
- 손상·비정상 EXIF 하나로 정상 이미지 전체가 실패하던 문제(길이 필드가 망가지면 판독기가 `OutOfMemoryError` 를 냈다). 방향을 정상으로 두고 경고를 남긴다
- 읽기 경로의 `SecurityException`(만료된 URI 권한 등)이 `ImageEncodeException` 으로 나가던 것을 ERRORS.md 대로 `ImageIoException` 으로
- 플랫폼 내부의 `IllegalArgumentException` 이 그대로 새던 것. IAE 통과는 직접 만든 `Resizer`·`CompressionStrategy`·`Encoder` 가 던진 것만
- 방향 적용을 축소 전에 해 회전한 전체 사본이 하나 더 생기던 것. 방향·자르기·축소를 행렬 하나로 한 번에 그려 최대 메모리를 줄였다
- 16비트 PNG·10비트 HEIF/AVIF 가 `RGBA_F16` 으로 인코딩까지 가던 것(메모리 2배, PNG 출력 16비트). 언제나 8비트 `ARGB_8888` 로 바꾼다. 넓은 색역은 sRGB 로 변환해 디코딩하고 HDR 게인맵은 뗀다
- `File`·`Uri` 입력을 압축 한 번에 3~4번 열던 것. 파일 기술자 하나를 되감아 쓴다(기술자를 못 주는 provider 는 이전처럼 다시 연다). EXIF 는 방향과 보존 태그를 한 번에 읽는다
- `ImageSource.stream` 이 한 배열을 키우며 복사해 순간 메모리가 읽은 크기의 2~3배이던 것. 64KiB 조각으로 담는다
- `writeTo(resolver, uri)` 가 `"wt"` 를 거부하는 provider 에서 날 런타임 예외를 내던 것. `"w"` 로 다시 열어 쓰고 남은 꼬리를 자르며, provider 의 런타임 예외는 `ImageIoException` 으로
- 메타데이터를 보존하면 결과에 `Orientation=0`(정의되지 않음)이 들어가던 것. 1 로 적는다
- 반투명 `backgroundColor` 에서 투명 영역이 검게 나오던 것. 흰색 위에 먼저 합성한다
- API 29 에서 `WEBP` 품질 100 이 무손실로 인코딩되던 것. 기본 인코더가 99 로 넘긴다. `WEBP_LOSSLESS` 는 언제나 품질 100 으로 부른다

### [0.0.1] - 발행

#### 추가
- `ImageCompressor` 와 DSL `ImageCompressor { ... }`, 프로필(`CompressionProfile`, 기본 Thumbnail·Upload·Avatar)
- 입력 `ByteArray`·`File`·`Uri`·`InputStream`(`ImageSource` 로 확장), 출력 바이트·`File`·`Uri`·`OutputStream`
- JPEG·PNG·WebP(손실/무손실, API 30 미만 대체 포함) 출력, HEIC·AVIF 입력(플랫폼 지원 시)
- 축소 디코딩(`inSampleSize`), `maxPixels` 제한, EXIF 방향 8종 적용, 투명 픽셀 합성(JPEG)
- 용량 목표: 품질 이분 탐색 후 크기 단계 축소, 결과 보고(품질·크기·시도 횟수)
- 메타데이터(위치 포함)는 기본 제거, `MetadataGroup` 으로 선택 보존
- 확장 지점 `Resizer`·`Encoder`·`CompressionStrategy`·`ImageSource`
- 오류: `ImageCompressionException` 계열 5종, 잘못된 설정은 `IllegalArgumentException`
