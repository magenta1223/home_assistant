# Runtime distribution 모듈 분리

- 상태: TODO
- 우선순위: P1
- 선행 작업: [onnx-embedding-runtime-migration.md](../p0/onnx-embedding-runtime-migration.md)

## 문제

`adapter.outbound.runtime.distribution`은 application/domain port를 구현하지 않는다. 외부
distribution의 manifest, HTTP download, SHA-256 검증, staging, publish와 Windows platform 검증을
제공하며 Ollama와 Qdrant installer가 함께 사용한다. ONNX 전환 후에는 model bundle 준비에도 같은
기반이 사용될 가능성이 있다.

이 코드는 단순 공용 문자열/파일 utility가 아니라 외부 artifact를 신뢰 가능한 runtime으로
설치하는 독립 lifecycle을 소유한다. 따라서 기능별 outbound port 구현만 두려는
`adapter-outbound`의 최상위 package에 놓기보다 명시적인 기반 모듈로 분리하는 편이 맞다.

## 목표

- distribution 설치 기반을 독립 `runtime-distribution` 모듈로 이동한다.
- 모듈은 application/domain/adapter를 의존하지 않고 JDK와 최소 logging dependency만 사용한다.
- Qdrant와 ONNX model bundle installer는 product manifest와 product-specific 검증만 소유한다.
- download, checksum, staging과 atomic publish의 공통 동작과 기존 실패 계약을 유지한다.
- 범용 package manager나 임의 OS installer framework로 확장하지 않는다.

## 범위

- `DistributionManifest`, `DistributionInstaller`, `AssetDownloader`, `HttpAssetDownloader`
- Windows x86-64 platform 검증
- checksum, staging directory, atomic/fallback publish와 cleanup
- 공통 installer 단위 테스트와 test fixture
- Qdrant/ONNX consumer의 모듈 의존성 변경

## 구현 순서

1. ONNX P0에서 확정한 model bundle 설치 요구사항과 기존 Qdrant 요구사항을 비교한다.
2. 공통인 manifest/download/checksum/staging/publish 계약과 product-specific extract/validation hook을
   구분한다.
3. `runtime-distribution` Gradle 모듈을 만들고 현재 공통 type과 테스트를 이동한다.
4. public API는 실제 Qdrant와 ONNX consumer가 요구하는 최소 surface만 노출한다.
5. Qdrant executable/archive 검증과 ONNX model/tokenizer 검증은 각 integration에 남긴다.
6. platform support를 현재 Windows x86-64 요구 이상으로 일반화하지 않는다.
7. partial download, checksum mismatch, unsafe archive path, incomplete install, concurrent publish와
   cleanup 회귀 테스트를 실행한다.
8. `setupEmbedding`, `setupQdrant`, `setupRuntime`을 통해 실제 consumer 조립을 검증한다.

## 완료 조건

- `runtime-distribution`가 독립 Gradle 모듈이며 application/domain/adapter를 의존하지 않는다.
- `adapter-outbound/runtime/distribution` package가 제거된다.
- Qdrant와 ONNX installer는 공통 설치 알고리즘을 중복 구현하지 않는다.
- product-specific entry point와 필수 파일 검증은 각 integration이 소유한다.
- 기존 download, checksum, staging, publish와 cleanup 테스트가 새 모듈에서 통과한다.
- `setupEmbedding`, `setupQdrant`, `setupRuntime`과 전체 `gradlew test`가 통과한다.

## 제외 범위

- Linux/macOS 지원 추가
- 범용 package manager, dependency resolver 또는 plugin framework 구현
- production startup에서 자동 다운로드 허용
- Qdrant/ONNX version 자체의 변경

## 현재 상태 (2026-09-06)

미구현이다. `runtime-distribution` Gradle 모듈은 없고 manifest, download, checksum과 publish 구현은
계속 `adapter-outbound/runtime/distribution`에 있다. ONNX 전환 이후 실제 두 consumer의 공통 요구가
확정될 때까지 P1로 유지한다.
