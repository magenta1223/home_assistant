# ONNX Runtime 기반 임베딩 전환

- 상태: TODO
- 우선순위: P0
- 선행 작업: [managed-embedding-server.md](../done/managed-embedding-server.md)

## 문제

현재 `multilingual-e5-base` 임베딩을 사용하려면 애플리케이션이 별도 Ollama 실행 파일과 모델
저장소를 설치하고, 자식 서버 프로세스를 `127.0.0.1:11435`에서 시작한 뒤 HTTP로 추론해야 한다.
`setupEmbedding`도 모델을 준비하고 검증하기 위해 임시 Ollama 서버를 실행한다.

이 구조는 임베딩 한 기능 때문에 다음 운영 책임을 만든다.

- Ollama 배포본과 모델을 별도로 설치하고 버전을 맞춰야 한다.
- 애플리케이션 시작·종료가 자식 프로세스, 전용 포트, readiness polling에 결합된다.
- 프로세스 시작 실패, 포트 충돌, 비정상 종료가 전체 memory 색인·검색과 애플리케이션 기동을 막는다.
- 배포 스크립트가 Java, Qdrant뿐 아니라 Ollama 프로세스 트리와 포트도 안전하게 정리해야 한다.
- setup과 정상 실행이 서로 다른 Ollama lifecycle을 중복해서 가진다.

임베딩은 외부 서버가 필요한 기능이 아니므로 JVM 프로세스 안에서 ONNX Runtime으로 직접
추론해 이 운영 경계를 제거한다. 임베딩이 memory 저장과 검색 모두에 사용되므로 잘못 전환하면
기존 Qdrant 벡터와 query 벡터가 호환되지 않아 검색 전체가 무효화된다. 따라서 단순 구현 교체가
아니라 모델 산출물, tokenizer, pooling, 정규화와 전체 재색인을 하나의 P0 전환으로 관리한다.

## 목표

- `TextEmbedder`와 application/domain 계약은 바꾸지 않고 outbound 구현만 ONNX Runtime 기반으로
  교체한다.
- `setupEmbedding`이 검증된 모델 bundle을 준비하되 프로세스를 시작하거나 포트를 열지 않게 한다.
- 정상 실행에서 Ollama 실행 파일, HTTP API, 자식 프로세스와 `11435` 포트 의존성을 제거한다.
- 현재 모델의 768차원 E5 의미 계약을 명시하고 ONNX 구현에서도 동일하게 재현한다.
- 기존 canonical memory를 새 임베딩으로 전부 재색인한 후 검증된 새 Qdrant collection으로
  안전하게 전환한다.
- 누락·손상된 모델, tokenizer 불일치와 native library 로딩 실패를 시작 시 명확하게 감지한다.
- 전환 전 Ollama 경로와 기존 Qdrant collection을 보존해 운영 검증 실패 시 되돌릴 수 있게 한다.

## 고정할 임베딩 계약

구현 전에 다음 동작을 테스트 가능한 계약으로 먼저 고정한다.

- 기준 모델: 공식 `intfloat/multilingual-e5-base`의 고정 revision
- 출력 차원: 정확히 768
- tokenizer: 해당 revision의 XLM-R tokenizer 파일과 special token 규칙
- 최대 token 길이, padding과 truncation 규칙
- model 입력 tensor 이름과 자료형: `input_ids`, `attention_mask` 및 실제 export signature
- 출력 선택, attention-mask mean pooling과 L2 normalization 방식
- 검색 query와 저장 passage에 적용할 E5 prefix 규칙
- blank text 거부와 앞뒤 공백 처리
- 같은 입력에 대한 결정성과 허용할 부동소수점 오차

기존 `qllama/multilingual-e5-base`가 내부적으로 적용하던 prompt, tokenizer, pooling 또는 양자화
동작을 추측하지 않는다. 현재 Ollama 출력과 공식 Hugging Face 기준 출력을 고정 corpus에서 각각
수집하고 차이를 확인한 뒤 위 계약을 확정한다.

## 범위

### 포함

- ONNX Runtime Java CPU 의존성과 Windows x86-64 native runtime 검증
- 고정 모델 revision에서 재현 가능한 ONNX bundle 생성·검증 절차
- tokenizer 실행 방식 결정과 Java/Windows 배포 검증
- JVM 내부 ONNX session을 소유하는 `TextEmbedder` 구현
- `setupEmbedding`, application composition, health와 종료 lifecycle 변경
- Ollama 설치·모델 pull·HTTP client·server runtime 제거
- 새 Qdrant collection 전체 재색인, 검증, 전환과 rollback 절차
- 단위·통합·검색 회귀·운영 성능 테스트
- Gradle task, 설정, 배포 스크립트와 운영 문서 정리

### 제외

- embedding model 종류나 768차원 계약의 동시 변경
- 초기 전환에서 GPU/CUDA 사용
- 기준 정확도 검증 전 INT8/FP16 양자화 적용
- hosted embedding API 도입
- XLM-R tokenizer 직접 구현
- 검증 전 기존 Ollama 파일이나 기존 Qdrant collection 삭제
- Qdrant 자체를 다른 vector store로 교체

## 구현 순서

### 1. 현재 임베딩 기준선 고정

1. 한국어, 영어, 혼합 언어, 숫자·날짜, 특수문자, 장문과 유사/비유사 문장으로 고정 corpus를
   만든다.
2. 현재 Ollama 구현에 대해 출력 차원, norm, 반복 호출 결정성, latency와 memory 검색 top-k를
   기록한다.
3. 실제 canonical memory 검색에서 반드시 찾아야 할 query-memory 사례를 회귀 fixture로 만든다.
4. 현재 Ollama 모델의 정확한 식별 정보와 digest를 기록한다.
5. 공식 Hugging Face PyTorch 출력도 같은 corpus로 생성해 Ollama 결과와 cosine similarity,
   ranking 차이를 비교한다.

이 단계에서 ONNX가 맞춰야 할 authoritative reference와 허용 오차를 확정한다. Ollama가 양자화된
모델이면 byte 단위 동일성을 요구하지 않고 공식 기준과 검색 품질을 각각 판정한다.

### 2. ONNX·tokenizer 기술 spike

1. `com.microsoft.onnxruntime:onnxruntime` CPU artifact가 현재 JDK와 production Windows에서
   native library를 정상 로딩하는지 최소 실행으로 확인한다.
2. 공식 모델의 고정 revision을 ONNX로 export하고 입력·출력 signature와 dynamic axes를
   기록한다.
3. tokenizer 구현은 다음 순서로 검증한다.
   - ONNX Runtime Extensions로 tokenizer를 graph에 포함하고 Java에서 prebuilt native artifact를
     재현 가능하게 배포할 수 있는지 확인
   - 불가능하면 검증된 JVM tokenizer library로 동일 token ID와 attention mask를 생성할 수 있는지
     확인
   - 어느 경로도 공식 tokenizer와 일치하지 않으면 구현을 진행하지 않고 blocker로 기록
4. 우선 FP32 모델로 tokenization, inference, mean pooling과 L2 normalization을 끝까지 구현한다.
5. 기준 corpus에서 Hugging Face reference와 수치 오차, Ollama 대비 검색 ranking을 비교한다.
6. startup 시간, 단건 latency, 동시 호출, resident memory를 production 사양에서 측정한다.

spike 결과로 tokenizer 경로, ONNX opset, session option, thread 수와 최종 artifact 구성을
결정한다. ONNX Runtime Extensions가 Windows Java용 별도 native build를 요구하면 그 운영 비용까지
포함해 채택 여부를 판단한다.

### 3. 재현 가능한 모델 bundle과 setup 구성

1. 공식 모델 repository와 revision, export tool 버전, opset, tokenizer 파일, license를 manifest에
   고정한다.
2. production startup에는 Python을 두지 않는다. Python/Optimum이 필요하면 project-owned ONNX
   bundle을 만드는 일회성 build 절차에서만 사용한다.
3. bundle은 모델, tokenizer, runtime metadata와 source/license 정보를 포함하고 SHA-256으로
   검증한다.
4. 큰 binary는 Git에 넣지 않고 고정 URI에서 내려받게 하며, 기존 공용 distribution 설치
   기반을 재사용할 수 있는지 확인한다.
5. `runtime/embedding/<bundle-version>/`처럼 모델 identity가 드러나는 version directory에
   원자적으로 설치한다.
6. `setupEmbedding`은 bundle 다운로드, checksum, 필수 파일과 ONNX session 로딩 probe까지만
   수행한다. 서버 시작과 모델 pull은 하지 않는다.
7. 설치 marker에는 bundle version과 checksum을 기록해 잘못된 조합을 설치 완료로 오인하지 않게
   한다.

### 4. JVM 내부 임베딩 adapter 구현

1. `adapter-outbound/embedding/onnx/`에 `TextEmbedder` 구현을 둔다.
2. 생성 시 모델·tokenizer 파일을 검증하고 ONNX session을 한 번만 생성한다.
3. 요청마다 tokenizer 결과를 tensor로 만들고, 추론 결과에 확정된 pooling과 normalization을
   적용한다.
4. 모든 tensor/result resource를 호출 단위로 닫고 session/environment의 application lifetime
   소유권을 명확히 한다.
5. 동시 호출 시 session 사용이 안전한지 검증하고 필요 이상의 전역 lock은 두지 않는다.
6. blank input, token truncation, 잘못된 출력 shape, NaN/Infinity와 zero norm을 명확히 거부한다.
7. `ApplicationServicesFactory`와 `MemoryReindex`는 새 factory를 사용하되 기존 `TextEmbedder`를
   소비하는 memory indexing/search 흐름은 변경하지 않는다.
8. 애플리케이션 시작 시 실제 embedding probe를 한 번 수행한다. 별도 process runtime을 만들지
   말고, ONNX resource의 `close` 책임만 application lifecycle에 연결한다.

### 5. 새 Qdrant collection 전체 재색인과 전환

Ollama로 만든 vector와 ONNX vector는 모델 이름과 차원이 같아도 동일하다고 가정하지 않는다.
기존 collection에 새 vector를 부분적으로 섞지 않는다.

1. 기존 collection과 다른 versioned collection을 생성한다.
2. 새 ONNX embedder를 사용해 모든 canonical memory를 새 collection에 전체 재색인한다.
3. SQLite canonical memory 수와 새 collection point 수, 실패·superseded outbox 수를 대조한다.
4. 고정 회귀 fixture와 실제 memory query로 top-k, score와 접근 권한 결과를 비교한다.
5. 검증이 끝난 뒤 application 설정을 새 collection으로 한 번에 전환하고 재시작한다.
6. Slack memory answer와 새 memory 저장·검색 smoke test를 수행한다.
7. 관찰 기간에는 기존 Ollama 설치와 기존 collection을 읽지 않는 상태로 보존한다.
8. 실패하면 이전 배포본과 이전 collection 설정으로 함께 rollback한다. embedder와 collection 중
   하나만 되돌리는 혼합 상태는 허용하지 않는다.

현재 `reindexMemories`의 outbox 기반 전체 재색인 흐름은 재사용하되, 대상 collection을 명시하고
운영 중인 기존 collection을 덮어쓰지 않도록 보강한다.

### 6. Ollama 운영 경계 제거

ONNX 전환과 새 collection 검증이 끝난 후 다음 항목을 제거한다.

- `OllamaEmbeddingService`, `ManagedOllamaEmbeddingFactory`, `OllamaServerRuntime`
- Ollama distribution installer와 model pull setup
- Ollama host/base URL/runtime directory 설정
- `11435` readiness, port ownership과 deploy shutdown 처리
- Ollama 전용 HTTP DTO, 테스트와 로그 문구

다음 문서를 새 구조에 맞게 갱신한다.

- root `AGENTS.md`의 setup/runtime 설명
- `docs/server-auto-deploy.md`와 관련 배포 P0 문서
- Gradle `setupEmbedding`/`setupRuntime` 설명
- 환경 변수 예시와 운영 복구 절차

기존 `EMBEDDING_MODEL` 문자열을 그대로 유지할 이유가 없으면 임의 model 선택 설정으로 남기지
않고, 고정 bundle identity를 코드와 manifest에서 단일 관리한다.

### 7. 검증과 production cutover

1. tokenizer token ID·attention mask를 공식 reference fixture와 비교한다.
2. pooling, mask 적용, L2 normalization과 768차원 shape를 단위 테스트한다.
3. ONNX 출력이 확정된 reference 오차 안에 드는지 corpus 전체에서 확인한다.
4. 같은 입력 반복, 장문 truncation, 다국어, 동시 호출과 resource close를 통합 테스트한다.
5. 누락·checksum 불일치·손상된 ONNX·native DLL 로딩 실패 시 setup/startup 오류를 검증한다.
6. `gradlew test`, `setupEmbedding`, `setupRuntime`, `reindexMemories`를 새 경로로 검증한다.
7. `HOMESERVER`에서 startup 시간, embedding latency와 memory 사용량을 기준선과 비교한다.
8. 별도 collection 재색인, 전환, health, Slack 질의와 rollback을 순서대로 rehearsal한다.
9. 운영 관찰이 끝난 뒤에만 Ollama runtime과 이전 collection 삭제를 별도 정리 작업으로 수행한다.

## 완료 조건

- `setupEmbedding`이 고정 revision과 checksum이 있는 ONNX bundle을 준비하고 실제 session probe까지
  성공하며, 그 과정에서 자식 서버나 listening port를 만들지 않는다.
- 정상 애플리케이션 실행에 Ollama executable, model store, HTTP client와 `11435` 포트가 필요하지
  않다.
- `TextEmbedder` 출력은 항상 768차원이고 유한하며 L2 norm이 허용 오차 안에서 1이다.
- tokenizer와 ONNX FP32 출력이 spike에서 확정한 공식 reference 허용 오차를 모든 고정 fixture에서
  만족한다.
- 기존 Ollama 대비 byte equality가 아니라, 고정 검색 사례의 기대 memory가 모두 top-k 안에
  유지되고 실제 corpus 평가에서 승인한 품질 기준을 만족한다.
- 모든 canonical memory가 새 collection에 재색인되며 memory 수, point 수와 실패 건수 검증이
  통과한다.
- 새 embedder와 새 collection이 항상 한 쌍으로 배포·rollback되고 혼합 vector 상태가 없다.
- 누락되거나 손상된 bundle은 setup 안내가 포함된 오류로 startup 전에 실패한다.
- concurrent embedding과 application shutdown에서 native resource leak이나 종료 hang이 없다.
- production에서 health, 새 memory 색인, semantic search와 Slack memory answer smoke test가
  통과한다.
- Ollama 제거 후 Gradle 전체 테스트와 배포 종료·복구 회귀 테스트가 통과한다.
- rollback rehearsal과 운영 관찰이 끝날 때까지 이전 배포본과 Qdrant collection이 보존된다.

## 주요 위험과 중단 조건

| 위험 | 대응 또는 중단 조건 |
|---|---|
| tokenizer 결과 불일치 | 공식 tokenizer fixture와 token ID가 일치하지 않으면 구현 진행 중단 |
| pooling/prefix 차이로 검색 품질 저하 | 공식 reference와 실제 retrieval 회귀를 모두 통과하기 전 cutover 금지 |
| ONNX Java native library 배포 실패 | production Windows/JDK 최소 실행 검증 전 의존성 확정 금지 |
| Extensions가 별도 native build를 요구 | 재현 가능한 binary 공급·checksum 경로가 없으면 다른 tokenizer 경로 선택 |
| FP32 memory/latency 과다 | 먼저 session/thread 설정을 측정하고, 정확도 기준 고정 후에만 INT8 별도 평가 |
| 기존 vector와 새 vector 혼합 | versioned collection 전체 재색인과 embedder-collection 묶음 전환 강제 |
| artifact 출처·license 불명확 | 제3자 pre-export 모델을 바로 사용하지 않고 공식 revision 기반 bundle과 provenance 기록 |

## 공식 기술 참고

- [ONNX Runtime Java 시작 문서](https://onnxruntime.ai/docs/get-started/with-java.html)
- [ONNX Runtime Extensions](https://onnxruntime.ai/docs/extensions/)
- [ONNX Runtime Extensions build](https://onnxruntime.ai/docs/extensions/build.html)
- [Hugging Face Optimum ONNX export](https://huggingface.co/docs/optimum-onnx/onnx/usage_guides/export_a_model)
- [Hugging Face ONNX Runtime optimization](https://huggingface.co/docs/optimum-onnx/en/onnxruntime/usage_guides/optimization)
- [intfloat/multilingual-e5-base](https://huggingface.co/intfloat/multilingual-e5-base)

## 현재 상태 (2026-09-06)

미구현이다. 현재 composition은 `ManagedOllamaEmbeddingFactory`를 사용하고 application startup이
프로젝트 관리형 Ollama server lifecycle을 소유한다. ONNX Runtime dependency와 model bundle은
아직 도입되지 않았다.
