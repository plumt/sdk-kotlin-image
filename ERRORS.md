# 오류와 실패 신호

## image-compress

예외는 두 계열뿐이다. 설정·인자가 틀리면 `IllegalArgumentException`, 그 밖의 실패는 `ImageCompressionException`(sealed)의 하위 타입이다.
코루틴 취소는 `CancellationException` 그대로 전파하며 감싸지 않는다. 조용한 대체값(실패를 숨기고 원본·낮은 품질을 돌려주기)은 없다.
메시지 문구는 바뀔 수 있으니 분기는 예외 타입으로 한다. 코루틴 디버그 모드의 스택 복구는 예외를 복사하면서 `cause` 를 원본 예외로 바꿀 수 있으므로 `cause` 체인을 끝까지 따라간다.

### IllegalArgumentException

| 언제 | 호출부가 할 일 |
|---|---|
| `quality` 가 1..100 밖 | 값을 바로잡는다 |
| `maxWidth`·`maxHeight`·`maxPixels` 가 1 미만 | 값을 바로잡는다 |
| `targetBytes` 가 1 미만, `minQuality` 가 1..100 밖, `dimensionStep` 이 (0,1) 밖, `minDimension` 이 1 미만 | 값을 바로잡는다 |
| `minQuality` 가 `quality` 보다 큼 | `minQuality` 를 낮추거나 `quality` 를 올린다 |
| `targetBytes` 와 `strategy` 를 함께 지정 | 하나만 쓴다 |
| `CenterSquareResizer`·`FitResizer`·`ResizePlan`·`EncodedImage`·`CompressionProfile` 이름 등 생성 인자 위반 | 값을 바로잡는다 |
| `ImageSource.stream` 의 `maxBytes` 가 1 미만 | 값을 바로잡는다 |
| 직접 만든 `Resizer` 가 원본 밖 영역을 계획 | `Resizer` 를 고친다 |
| 직접 만든 `Encoder`·`CompressionStrategy` 가 던진 `IllegalArgumentException` | 그대로 전달된다 |

### ImageCompressionException 하위 타입

| 타입 | 언제 | `cause` | 호출부가 할 일 |
|---|---|---|---|
| `ImageDecodeException` | 크기를 읽을 수 없음(손상·미지원 형식·빈 입력), 디코딩 결과 없음 | 없음 | 파일을 확인한다. HEIC/AVIF 는 기기 API 레벨을 확인한다 |
| `ImageIoException` | 입력 열기·읽기 실패(파일 없음, URI 열기 실패, 스트림 한도 초과), 출력 쓰기 실패 | `IOException` 등 | 경로·권한을 확인한다 |
| `ImageEncodeException` | 인코더가 false·빈 결과·예외, 메타데이터 쓰기 실패, 예기치 않은 내부 실패 | 있으면 원래 예외 | 형식을 바꾸거나 인코더를 확인한다 |
| `ImageOutOfMemoryException` | 처리 중 `OutOfMemoryError` | `OutOfMemoryError` | `maxDimension`·`maxPixels` 를 줄인다 |
| `TargetSizeNotReachedException` | 최소 품질·최소 크기까지 내려도 `targetBytes` 초과 | 없음 | `targetBytes` 를 올리거나 `minDimension`·`minQuality` 를 낮춘다. `smallestBytes`·`width`·`height` 로 가능한 최소를 알 수 있다 |

### 값으로 알리는 신호

| 신호 | 의미 |
|---|---|
| `CompressedImage.quality == null` | 품질이 없는 형식(PNG·무손실 WebP) |
| `CompressedImage.sampleSize > 1` | 축소 디코딩을 썼다 |
| `CompressedImage.preservedMetadata` 가 비어 있음 | 켠 묶음이 없거나 원본에 해당 태그가 없었다 |
