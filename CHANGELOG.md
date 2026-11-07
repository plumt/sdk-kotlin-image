# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## image-compress

### [Unreleased]

### [0.0.2] - 2026-11-07

공개 API 는 0.0.1 과 바이너리 호환이다(추가만).

#### 추가
- `CompressedImage.warnings` 와 `CompressionWarning.EXIF_UNREADABLE` — 손상된 EXIF 를 대체 처리했음을 결과에 표시
- 실기기 계측 시험(`src/androidTest`, `-PdeviceImageDir` 로 사진 폴더 지정). CI 는 돌리지 않는다

#### 수정
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

#### 변경
- 용량 목표 탐색: 줄인 크기에서는 최소 품질부터 시도하고, 용량이 픽셀 수에 비례한다고 보고 다음 크기를 추정해 건너뛴다. 도달 불가능한 목표의 인코딩 횟수가 수십 회에서 몇 회로 준다. 같은 입력에서 0.0.1 과 결과 크기·품질이 다를 수 있다
- `targetBytes` 와 `strategy` 를 함께 주면 IAE 이던 것을, 나중에 대입한 쪽이 쓰이게 했다(용량 목표가 있는 프로필 위에 `strategy` 를 덮어쓸 수 있다)
- `writeTo(file)` 는 임시 파일에 쓴 뒤 이름을 바꾼다. 실패해도 기존 파일을 지우지 않는다
- 문서: 발행 상태, WebP 품질의 실제 동작, 색 프로필·게인맵 처리, HEIC 메타데이터 보존 제약, 동시 실행 권고, `centerSquare` 의 확대 기본값

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
