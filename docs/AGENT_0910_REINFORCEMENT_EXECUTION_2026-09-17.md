# Agent_0910 보강 실행 결과

작성일: 2026-09-17

대상: `HJP-limited/HJP_dataset_gen_by_v1`, branch `Agent_0910`

시작 HEAD: `c514105191e2548ff4e897f9478dd827207c0d4d`

구현 완료 HEAD(이 보고서 추가 전): `6f8f1c6`

## 1. 판정

`Agent_0910`의 확인된 checkpoint 누락을 복구하고, 독립 build·test·E-3.7 검증 경로를 보강했다.

```text
AGENT_0910_REINFORCEMENT_VERIFIED
```

production Agent의 Router, memory, policy, prompt, Tool ownership, Tool schema에는 semantic 변경을
가하지 않았다. 현재 dirty 원본 working tree도 변경하지 않았다. 모든 작업은
`/private/tmp/agent0910-audit` isolated clone에서 수행했다.

## 2. 반영한 변경

### 2.1 Build blocker 복구

`AgentKernel`이 이미 기록하던 route/runtime diagnostics 6개 필드를 `TurnDiagnostics`와
`asMap()`에 복구했다.

- `routeSearchRequired`
- `routeSearchQueryPresent`
- `directoryMatchCount`
- `runtimeStage`
- `nameCandidateCount`
- `personMarkedCandidateCount`

파일:

- `agent-core/src/main/kotlin/com/hjp/agent/core/AgentDiagnostics.kt`

이 변경은 evaluation/debug provenance를 완성한다. 모델 입력, Router 결과, Tool 선택 및 실행에는
사용되지 않는다.

### 2.2 E-3.7 evaluation provenance 정렬

Android instrumentation이 과거 E-3.5 asset/manifest를 기록하던 경로를 current official E-3.7로
정렬했다.

- asset: `agent_eval/eval_set_v1_e37.json`
- Gold source: `tools/agent_eval_multiturn_v1/data/eval_set_v1_e37.json`
- contract: `E-3.7`
- SHA-256: `7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961`

파일:

- `app/build.gradle.kts`
- `app/src/androidTest/java/com/example/hjp/MultiturnToolCallDeviceEvalInstrumentedTest.kt`

production app behavior에는 영향을 주지 않고 androidTest asset과 manifest metadata만 변경한다.

### 2.3 Official scorer 복구

다음 파일을 release branch에 추가했다.

- `tools/agent_eval_multiturn_v1/score.py`
- `tools/agent_eval_multiturn_v1/test_score_temporal_anchor.py`
- `tools/agent_eval_multiturn_v1/test_e37_official_contract.py`
- `tools/agent_eval_multiturn_v1/README.md`

검증된 temporal semantics:

- relative datetime은 scenario-local `get_current_datetime` ToolResult를 사용
- 서로 다른 scenario runtime date 분리
- timezone offset이 포함된 runtime datetime의 local date 사용
- temporal anchor가 없으면 host date를 추정하지 않고 mismatch
- absolute datetime unaffected
- non-calendar scoring unaffected

### 2.4 자동 checkpoint gate

추가:

- `scripts/verify-agent-checkpoint.sh`
- `tools/agent_eval_multiturn_v1/test_release_wiring.py`

검증 범위:

- Python Gold/scorer tests
- `:agent-core:test`
- debug APK build
- androidTest APK build
- production Tool catalog 6종
- Android evaluation E-3.7 path/SHA
- external asset manifest logical inventory와 relative path

### 2.5 문서와 hygiene

수정:

- `README.md`
- `docs/PERFORMANCE_VALIDATION.md`
- `.gitignore`

문서에는 E-3.7 current official contract, A-56 trace re-score라는 baseline provenance,
post-A-63 fresh full run이 아니라는 제한, historical E-3.2 구분, production `CPU_ONLY` wiring과
evaluation GPU configuration의 차이를 반영했다.

`.gitignore`는 module별 build output과 model/APK/partial artifact가 실수로 stage되지 않도록
보강했다.

## 3. Source parity 결과

보강 후 다음 production module을 현재 로컬 Agent source와 재대조했다.

- `agent-contract`
- `agent-core`
- `llm-litert`
- `search-core`
- `tool-contract`
- `tool-contact`
- `tool-datetime`
- `tool-android-intents`
- `app/src/main`
- 주요 Gradle/settings 파일

결과:

- 공통 production module source diff 0
- `app/src/main/java/com/example/hjp/agent`의 로컬 legacy source만 release branch에 없음
- legacy source는 현재 modular Agent의 dependency/import/build graph에 포함되지 않으므로 추가하지 않음
- production Tool registry는 6종으로 고정됨

## 4. Contract integrity

E-3.7 검증 결과:

| 항목 | 결과 |
|---|---:|
| SHA-256 | PASS |
| scenario | 400 |
| turn | 1,918 |
| duplicate scenario | 0 |
| generator byte reproduction | PASS |
| primary expected/forbidden conflict | 0 |

E-3.7에는 15개 unique-name lookup turn에서 legacy representation이 존재한다. primary search-first
route의 `forbidden_tools`에는 `get_contact`가 있지만, `alternative_expected_calls`와
`allowed_tool_sequences`는 Policy A의 direct unique-name read를 명시적으로 허용한다. 이는 이번
작업에서 Gold semantics를 변경하지 않고 다음 경계로 고정했다.

```text
overlap scenario = 15
turn = 1 only
tool = get_contact only
```

`test_e37_official_contract.py`가 이 예외의 확대를 방지한다. 새로운 Tool/turn에 같은 표현이
추가되면 새 contract revision 없이 test가 실패한다.

## 5. 실행 검증

### 5.1 Python contract/scorer

```text
17 tests PASS
```

포함:

- E-3.3 ordinal tests
- E-3.5 ambiguity tests
- E-3.7 hash/scope/generator/Policy-A tests
- release wiring tests
- A-15 temporal-anchor scorer tests

### 5.2 Agent core

```text
./gradlew :agent-core:test --offline --rerun-tasks
BUILD SUCCESSFUL
```

deprecated `ContactTurnReferenceResolver` test warning만 있으며 failure는 없다.

### 5.3 Android build

환경:

- OpenJDK 17
- Android SDK 36.1

결과:

```text
:app:assembleDebug PASS
:app:assembleDebugAndroidTest PASS
syncRyeongEvalAssets: 3 assets verified
```

native `.so` strip warning가 있었으나 Android build는 성공했고 libraries는 원본 상태로 package됐다.

### 5.4 통합 gate

```text
scripts/verify-agent-checkpoint.sh
PASS
```

이 스크립트가 Python tests, core tests, debug APK, androidTest APK를 순서대로 검증했다.

## 6. 외부 asset과 runtime 범위

repository에는 대형 model binary를 추가하지 않았다. `external_assets/MANIFEST.json`에 다음 logical
asset과 checksum만 유지한다.

- Gemma 4 E2B LiteRT-LM artifact
- EmbeddingGemma 300M
- SentencePiece model

APK/androidTest compile은 model binary 없이 성공했다. 실제 Gemma/EmbeddingGemma inference와 device
smoke는 이번 source checkpoint 보강 범위에 포함하지 않았다.

## 7. Security/repository hygiene

검사 결과:

- credential/token/private key pattern: 0
- 개인 `/Users/...` 또는 `/private/tmp/...` tracked content: 0
- tracked APK/model/partial: 0
- 20 MiB 초과 신규 source file: 0
- `.DS_Store`: 0
- unrelated physical trace/log: 0

검증 과정에서 생성된 build output은 `gradlew clean`으로 제거했다.

## 8. Commit 구성

1. `04a6b83` — `Restore Agent diagnostics contract`
2. `c6294e7` — `Restore reproducible E-3.7 scoring workflow`
3. `6f8f1c6` — `Align Agent checkpoint docs and validation gates`

이 보고서는 위 구현 commit 이후 별도 documentation commit으로 포함된다.

## 9. 남은 제한

- post-A-63 production Agent의 fresh physical full-400은 실행하지 않았다.
- DEV-0010/REG-0014 runtime-conditioned ambiguity policy는 pending 상태다.
- E-3.7의 15개 direct-read alternative representation은 bounded compatibility로 남아 있다.
- 실제 model/device runtime 검증에는 manifest에 선언된 외부 asset과 Android device가 필요하다.
- 이 보강은 `integration/dev`의 semantic divergence를 해결하지 않는다. 해당 저장소는 별도 작업이다.

## 10. 최종 상태

`Agent_0910`은 다음 조건을 만족한다.

- 독립 clone에서 compile 가능
- core tests PASS
- debug/androidTest APK build 가능
- current official E-3.7 artifact와 instrumentation provenance 일치
- official scorer와 temporal regression test 포함
- checkpoint 검증 명령 제공
- production semantic 변경 없음

다음 작업은 이 branch를 clean clone으로 다시 받아 동일 gate를 실행하는 remote post-push 검증이다.
