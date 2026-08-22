# HJP Agent — `Agent_0822`

Android 온디바이스 명함 AI 에이전트의 2026-08-22 스냅샷. **성능 평가를 돌릴 수 있는 상태**로 올린 브랜치다.

기준 문서는 `CLAUDE.md`이고, 구조·검색·평가 문서는 `docs/`에 있다.

---

## 1. 이 브랜치에 있는 것

| | |
|---|---|
| 에이전트 소스 | `app`, `agent-contract`, `agent-core`, `search-core`, `tool-contact`, `tool-contract`, `tool-android-intents`, `tool-datetime`, `llm-litert` |
| main / test / androidTest 소스 | 96 / 125 / 7 개 |
| 평가 하네스 | `app/src/test/java/com/example/hjp/eval/` (Ryeong 호환성 평가, mutation self-test, canary) |
| 동결 평가 입력 | `app/src/test/resources/ryeong/` — 시나리오 130개, 명함 1,000장 |
| 시나리오 exporter | `tools/ryeong_multiturn_v4` (upstream `1caec3a2`), `tools/ryeong_multiturn_v5` (upstream `9f359c7`) |
| 검색 벤치마크 | `tools/ryeong_search_benchmark` |
| 평가 증거·freeze manifest | `integration_evidence/evaluation`, `integration_evidence/upstream`, `integration_evidence/production_eval` |

## 2. 일부러 뺀 것

* **모델 파일 전부** — `.litertlm`(최대 2.5GB), `embeddinggemma-300m.tflite`(179MB), `sentencepiece.model`.
  GitHub 파일 크기 한도를 넘고, 저장소가 무거워진다. 받는 방법은 §5에 있다.
* `build/` 산출물, APK, 스냅샷 디렉터리, 과거 사이클 번들 — 재생성 가능하거나 이력일 뿐이다.

모델 없이도 **아래 1~3번 평가는 그대로 돌아간다.** 4번만 모델이 필요하다.

---

## 3. 준비

* JDK **21**
* Android SDK **36.1**, build-tools 36.x
* `local.properties`에 `sdk.dir=<Android SDK 경로>` (이 파일은 커밋되지 않는다)

```bash
git clone -b Agent_0822 https://github.com/HJP-limited/HJP_dataset_gen_by_v1.git
cd HJP_dataset_gen_by_v1
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS 예시
export JAVA_HOME=<JDK 21 경로>
```

---

## 4. 돌릴 수 있는 평가

### 4.1 전체 JVM 회귀 — 모델 불필요

```bash
./gradlew test --continue
```

이 스냅샷 기준값: **90 suites / 670 tests / 0 failures / 0 errors / 1 skipped.**
skip 1건은 §4.3의 공식 평가로, 환경변수를 주지 않으면 실행되지 않는다.

### 4.2 Ryeong 시나리오 재수출 (결정성 확인) — 모델 불필요

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/ryeong_multiturn_v4/export_scenarios.py \
  --out /tmp/scenarios_v1.json
shasum -a 256 /tmp/scenarios_v1.json app/src/test/resources/ryeong/scenarios_v1.json
```

두 SHA가 같아야 한다 — `c5c238884652ab7351b7384bef0ac6ba0eaa85de3428b29b2499372dfd563f42`.
exporter는 upstream `build_scenarios()`를 그대로 호출하고 `PYTHONHASHSEED=0`을 고정하므로
어느 기기에서 돌려도 바이트가 같다. v5는 upstream 최신 tip(`9f359c7`) 기준 139/386/22를 낸다.

### 4.3 Ryeong 검색 멀티턴 호환성 (공식) — 모델 불필요, keyword-only

```bash
RYEONG_OFFICIAL_RUN=true \
RYEONG_OFFICIAL_OUT=<결과를 쓸 디렉터리> \
RYEONG_GIT_HEAD=$(git rev-parse HEAD) \
./gradlew :app:testDebugUnitTest \
  --tests 'com.example.hjp.eval.ryeong.RyeongOfficialCompatibilityRunTest' --rerun-tasks
```

`result.json` / `gate_verdict.json` / `run_status.json` 3종을 원자적으로 쓴다.
실행 전에 `integration_evidence/evaluation/ryeong_official_v1/freeze/jvm_keyword/PRE_RUN_FREEZE_MANIFEST.json`의
SHA를 확인하고, 하나라도 어긋나면 돌리지 마라.

> **이 축이 재는 것**: 검색 라우팅, focus 유지, 후속 질문, top-5 검색 순위, 시나리오 간 세션 격리.
> **재지 않는 것**: compose·calendar·update, typed outcome, 도구 호출 순서. 전체 Tool agent 성능이 아니다.

`v1` 실행 결과는 `integration_evidence/evaluation/ryeong_official_v1/`에 봉인돼 있다.
판정은 `INVALID RUN`이고 그 사유(검색 전용 턴에서 action tool 1회)와 원인 분석이 같은 경로에 있다.
**결과를 본 뒤 dataset·gate·adapter를 고쳐 같은 버전으로 다시 돌리지 마라.** 고칠 일이 생기면 새 버전으로 만든다.

### 4.4 실기기 actual-model 평가 — 모델 필요

물리 ARM64 기기, `.litertlm` 배치, EmbeddingGemma asset이 모두 준비돼야 한다.
절차와 선행 조건은 `integration_evidence/evaluation/ryeong_official_v1/final/FINAL_EVALUATION_REPORT.md` §4·§15에 있다.
이 스냅샷 시점에서는 **아직 실행되지 않았다**(`NOT RUN`).

---

## 5. 모델 받기

| 역할 | 파일 | 두는 곳 |
|---|---|---|
| 에이전트 LLM | `hjp-agent.litertlm` | 앱 전용 외부 files 디렉터리의 `models/` |
| 임베딩 | `embeddinggemma-300m.tflite`, `sentencepiece.model` | `app/src/modelAssets/assets/models/` (디렉터리를 직접 만든다) |

파일 자체는 팀 공유 드라이브에서 받는다. 경로 계약은 `CLAUDE.md` 마지막 절과
`app/src/main/java/com/example/hjp/AppContainer.kt`, 복구 절차는 `docs/AGENT_MODEL_RECOVERY.md`에 있다.
**모델은 크기 + SHA-256으로 식별된다** — 이름만 바꿔서 다른 파일을 넣으면 거부된다(`ModelDeploymentResolver`).

임베딩 모델이 없으면 검색은 **keyword-only**로 자동 폴백한다. 그 결과를 semantic 성능으로 보고하면 안 된다.

---

## 6. 수치를 다룰 때 지킬 것

* **검색 평가 수치와 멀티턴 수치를 합치지 않는다.** 분모가 다르다.
* **Ryeong 호환성 축과 Production Agent Multiturn V4를 하나의 점수로 합치지 않는다.**
  Ryeong은 검색·focus·후속 질문의 하위 평가다. 커버리지 표는
  `integration_evidence/evaluation/ryeong_to_production_multiturn_adapter_v1/coverage_matrix.md`에 있다.
* **keyword fallback을 semantic 실행으로 쓰지 않는다.**
* **actual Gemma를 돌리지 않았으면 generation 지표는 `NOT_RUN`이다.** 추정치를 만들지 않는다.
* 성능 threshold는 결과를 본 뒤 정하지 않는다. 공식 실행 전에 사전 등록한다.

## 7. 알려진 계약 차이

Ryeong 시나리오의 문구 상당수가 현재 production 라우터에서 검색으로 가지 않는다.

```
"제갈민씨 찾아줘"       → DialogueAct.OTHER,    도구 0회
"제갈민씨 명함 찾아줘"  → CONTACT_SEARCH,       순위 반환
```

`v1` 공식 실행에서 377턴 중 `search_contacts` 호출이 **0회**였던 이유가 이것이다.
**adapter 결함이 아니라 두 계약의 차이**이며, 이 결과를 근거로 production 라우터나 동결 시나리오를
말없이 고치면 안 된다. 상세는 `integration_evidence/evaluation/ryeong_official_v1/final/FINAL_EVALUATION_REPORT.md` §7·§21.

---

## 8. 데이터

`app/src/test/resources/ryeong/cards_eval1000.json`(1,000장)과
`app/src/main/assets/cards/business_cards.json`(2장)은 **전부 합성 데이터**다.
실제 개인정보는 들어 있지 않다. 이메일은 생성된 도메인, 전화는 더미 번호다.
