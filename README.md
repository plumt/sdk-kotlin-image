# sdk-kotlin-image

안드로이드 이미지 모음 라이브러리. 업로드·표시용으로 이미지를 **메모리 안전하게 줄이고 압축**하며, **위치(GPS) 등 메타데이터를 기본으로 제거**한다.

| 모듈 | 좌표 | 상태 |
|---|---|---|
| `image-compress` | `net.plumt.image:image-compress` | 0.0.2 |

## image-compress

```kotlin
dependencies {
    implementation("net.plumt.image:image-compress:0.0.2")
}
```

```kotlin
val compressor = ImageCompressor {
    maxDimension = 1920
    format = ImageFormat.WEBP
    quality = 80
    targetBytes = 500_000          // 이 크기 이하가 될 때까지 품질 이분 탐색 -> 크기 단계 축소
}

val result = compressor.compress(File("photo.jpg"))          // suspend, 기본 Dispatchers.IO, 취소 가능
result.writeTo(File(cacheDir, "upload.webp"))
println(result)  // CompressedImage(WEBP 1920x1440, 431022B, quality=71, source=4032x3024, sampleSize=2, attempts=5, preserved=[])
if (CompressionWarning.EXIF_UNREADABLE in result.warnings) { /* 원본 EXIF 가 손상돼 방향을 정상(0°)으로 두었다 */ }
```

입력: `ByteArray`, `File`, `Uri`(`ContentResolver`), `InputStream`, 또는 직접 구현한 `ImageSource`. 출력: `ByteArray`(`result.data`), `File`, `Uri`, `OutputStream`.
출력 형식: JPEG, PNG, WebP 손실(`WEBP`)·무손실(`WEBP_LOSSLESS`). HEIC(API 28+)·AVIF(API 31+)는 플랫폼이 지원하는 기기에서 **입력으로만** 읽는다.

### 개인정보: 위치 정보는 기본으로 제거된다

사진의 EXIF 에는 위도·경도·고도, 촬영 시각, 기기 모델·일련번호, 작성자가 들어 있다. 이를 그대로 올리면 집이나 직장 위치가 새는 일이 흔하다.

- 어떤 설정에서도 원본 바이트를 그대로 돌려주지 않는다. 이미 작아서 줄일 필요가 없어도 **다시 인코딩**하므로 EXIF·XMP·IPTC·내장 썸네일이 모두 사라진다
- 남기려면 `preserveMetadata = setOf(MetadataGroup.CAMERA, ...)` 처럼 **묶음을 명시**한다. `LOCATION` 은 위치 노출을 감수한다는 선택이다
- 허용 목록 방식이다. 일련번호·카메라 소유자·UserComment·MakerNote·썸네일은 어떤 묶음을 켜도 복사하지 않는다. 방향은 픽셀에 이미 반영했으므로 보존 묶음을 켜면 방향 태그를 정상(1)으로 적는다
- 용량 목표와 함께 쓰면 메타데이터를 붙인 **뒤의** 크기로 맞춘다
- HEIC·AVIF 원본의 보존 태그는 플랫폼 판독기(`MediaMetadataRetriever`)가 읽을 수 있을 때만 남는다. 못 읽으면 `preservedMetadata` 가 빈 집합으로 그대로 보고된다
- 색: 넓은 색역(Display P3 등) 사진은 API 26+ 에서 sRGB 로 **변환해** 디코딩한다. 결과는 sRGB 라 색 프로필이 없어도 같은 색으로 보이며, 넓은 색역 자체는 사라진다. API 34+ 의 HDR 게인맵(Ultra HDR)도 떼어 SDR 로 낸다

### 메모리 안전

- 크기만 먼저 읽고(`inJustDecodeBounds`), 목표보다 작아지지 않는 가장 큰 `inSampleSize` 로 **축소 디코딩**한다. 목표가 작으면 원본 해상도로 디코딩하지 않는다
- 방향·자르기·축소는 행렬 하나로 **한 번에** 그린다. 회전한 전체 사본을 따로 만들지 않아 최대 메모리는 축소 디코딩본과 결과 하나다
- 16비트 PNG·10비트 HEIF/AVIF 는 플랫폼이 픽셀당 8바이트(`RGBA_F16`)로 디코딩한다. 결과는 언제나 8비트 `ARGB_8888` 로 바꿔 인코딩한다(PNG 출력도 8비트)
- `maxPixels`(기본 4096x4096)로 결과 픽셀 수를 제한한다
- 중간 Bitmap 은 성공·실패·취소 어느 경로에서나 recycle 한다. `OutOfMemoryError` 는 `ImageOutOfMemoryException` 으로 알린다
- `File`·`Uri` 입력은 압축 한 번에 **한 번만** 연다(파일 기술자를 되감아 다시 읽는다). 클라우드 provider 가 내용을 다시 받지 않는다. 기술자를 줄 수 없는 provider 는 스트림을 다시 연다
- `InputStream` 은 전부 메모리로 읽으므로 한도(`ImageSource.stream` 의 `maxBytes`, 기본 64MiB)가 있다. 64KiB 조각으로 담아 메모리는 읽은 크기만큼만 쓴다
- 크기 맞춤 때문에 사본을 만들 때는 기준 Bitmap 하나에서만 줄이고, 쓴 사본은 바로 회수한다
- 여러 장을 동시에 압축하면 장마다 Bitmap 을 잡는다. `dispatcher = Dispatchers.IO.limitedParallelism(2)` 처럼 동시 실행 수를 제한한다

### 용량 목표

`targetBytes` 를 주면 `TargetSizeStrategy` 가 동작한다. 결과는 같은 입력이면 항상 같다.

1. 설정 품질로 인코딩해 들어가면 끝
2. `minQuality`(기본 30)로 들어가면 사이를 **이분 탐색**해 목표 안에서 가장 높은 품질을 찾는다(선형 반복 아님)
3. 최소 품질로도 넘으면 긴 변을 적어도 `dimensionStep`(0.85)배로 줄인다. 용량이 픽셀 수에 비례한다고 보고 목표에 들어갈 크기를 추정해 그보다 작으면 바로 건너뛴다. 줄인 크기에서는 최소 품질부터 시도해 실패한 단계마다 인코딩이 한 번이다. `minDimension`(64px)까지 가서도 못 맞추면 `TargetSizeNotReachedException`
4. 결과의 `quality`·`width`·`height`·`sizeInBytes`·`attempts` 가 최종값이다

목표보다 큰 결과를 조용히 돌려주지 않는다. PNG·무손실 WebP 는 품질이 없어 크기 축소만 쓴다.
`targetBytes` 와 `strategy` 는 나중에 대입한 쪽이 쓰인다(`ImageCompressor(CompressionProfiles.Upload) { strategy = MyStrategy }` 가 된다).

### 확장 지점

| 인터페이스 | 역할 | 기본 구현 |
|---|---|---|
| `ImageSource` | 입력 출처 | `bytes`/`file`/`uri`/`stream` |
| `Resizer` | 자를 영역과 출력 크기 계획(순수 함수) | `FitResizer`, `CenterSquareResizer` |
| `Encoder` | Bitmap -> 바이트 | `Encoder.Default`(`Bitmap.compress`) |
| `CompressionStrategy` | 몇 번·어떤 순서로 인코딩할지 | `SinglePassStrategy`, `TargetSizeStrategy` |
| `CompressionProfile` | 이름 붙인 설정 묶음 | `CompressionProfiles.Thumbnail/Upload/Avatar` |

```kotlin
val Banner = CompressionProfile("banner") { maxWidth = 1200; maxHeight = 400; format = ImageFormat.WEBP }
val compressor = ImageCompressor(Banner) { quality = 70 }   // 프로필 적용 뒤 덮어쓰기
```

DI 프레임워크에 의존하지 않는다. 압축기는 상태가 없어 하나를 만들어 공유해도 된다.

### 오류

잘못된 설정·인자는 `IllegalArgumentException`(받은 값과 허용 범위 포함). 그 밖의 실패는 `ImageCompressionException` 의 하위 타입이며 원인은 `cause` 에 보존한다. 코루틴 취소는 `CancellationException` 그대로 전파한다.
권한이 없거나 만료된 URI(`SecurityException`)는 읽기·쓰기 모두 `ImageIoException` 이다. 손상된 EXIF 는 압축을 실패시키지 않고 방향을 정상(0°)으로 둔 뒤 `result.warnings` 에 `EXIF_UNREADABLE` 을 남긴다. 전부 [ERRORS.md](ERRORS.md).

### 출력 쓰기

- `writeTo(file)` 는 같은 폴더의 임시 파일에 다 쓴 뒤 이름을 바꾼다. 도중에 실패하거나 프로세스가 죽어도 반쯤 쓴 파일이 남지 않고 기존 파일은 그대로다
- `writeTo(resolver, uri)` 는 `"wt"` 로 열고, provider 가 그 모드를 거부하면 `"w"` 로 열어 쓴 뒤 남은 꼬리를 자른다

### 요구사항과 근거

- minSdk 23, compileSdk 36, Java 11 바이트코드
- WebP: API 30+ 는 `WEBP_LOSSY`/`WEBP_LOSSLESS`, 미만은 구형 `WEBP` 상수. 기본 인코더는 API 29 의 `WEBP` 품질 100 을 99 로 넘긴다(구형 상수의 100 은 무손실이 된다). `WEBP_LOSSLESS` 는 언제나 품질 100 으로 부른다 — API 30+ 에서는 압축 노력, API 29 에서는 무손실이다. **API 23~28 에는 무손실 WebP 경로가 없어 손실로 인코딩된다**(무손실이 필요하면 PNG)
- 방향은 형식과 관계없이 EXIF 방향(1..8)을 SDK 가 직접 픽셀에 적용한다. HEIC·AVIF 의 컨테이너 회전(`irot`)은 androidx ExifInterface 가 방향 태그로 돌려준다. `BitmapFactory` 는 어떤 형식에서도 방향을 픽셀에 반영하지 않으므로 두 번 돌지 않는다(실기기 계측 시험으로 고정)
- 런타임 의존: `kotlinx-coroutines-core`(공개 API 에 노출), `androidx.exifinterface`(내부 전용), `androidx.annotation`(`@RequiresApi` 만, exifinterface 가 원래 끌어오는 것과 같은 버전)
- 메타데이터를 보존할 때만 `java.io.tmpdir`(안드로이드에서는 앱 캐시)에 임시 파일을 잠깐 쓴다
- 메인 스레드에서 불러도 작업은 지정한 디스패처(기본 `Dispatchers.IO`)에서 돈다

### 다른 구현에서 옮길 때

- `centerSquare(size)` 는 짧은 변이 `size` 보다 작은 원본을 **키우지 않는다**. 언제나 `size`x`size` 가 필요하면 `centerSquare(size, allowUpscale = true)` 를 쓴다(기본 `Avatar` 프로필도 키우지 않는다)
- 원본을 그대로 돌려주는 경로가 없다. 이미 작은 사진도 다시 인코딩된다

### 실기기 계측 시험

`image-compress/src/androidTest` 는 실제 사진으로 방향·메타데이터 제거·메모리를 확인한다. 사진은 저장소에 넣지 않고 폴더를 지정해 돌린다.
CI 는 기기가 없어 이 시험을 돌리지 않는다(`build` 는 `connected*` 를 부르지 않는다).

```bash
./gradlew :image-compress:connectedDebugAndroidTest -PdeviceImageDir=/path/to/photos
```

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `image-compress-v0.0.1`)이다. 1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `image-compress/api/*.api` 로 고정하고 CI 가 대조한다. 라인 커버리지 90% 미만이면 빌드가 실패한다(Kover).

## 라이선스

[Apache License 2.0](LICENSE)
