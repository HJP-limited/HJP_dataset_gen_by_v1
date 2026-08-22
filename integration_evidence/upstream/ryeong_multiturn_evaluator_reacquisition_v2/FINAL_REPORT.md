# Ryeong 멀티턴 평가 자료 재확보 및 실행 가능성 검증

## 1. 최종 판정 — 두 축

| 축 | 판정 |
|---|---|
| **원본 Ryeong 평가 재현 가능성** | **BLOCKED — EXACT DEPENDENCY MISSING** |
| **현재 Android/Kotlin 에이전트 평가 가능성** | **BLOCKED — ADAPTER CONTRACT MISMATCH** |
| 종합 | **PARTIALLY READY** — 원본을 실제로 재확보하고 끝까지 정적 검증했으나, 오늘 어느 축도 평가를 실행할 수 없다 |

**이전 감사의 `BLOCKED — REFERENCE REPOSITORY NOT PRESENT`는 틀렸다.** 그 감사는 원격을
한 번도 조회하지 않고 로컬 부재만으로 결론지었다. 원격 브랜치는 실재한다.

---

## 2. 원격 branch 확보

```
$ git ls-remote --heads https://github.com/HJP-limited/ryeong.git refs/heads/llm-integration-work
1caec3a23d0c1ee8f6a8d4a5e54160dbb2dc81bc	refs/heads/llm-integration-work
```

세션 scratchpad(프로젝트 트리 밖, `../ryeong`과도 분리)에 `--single-branch`로 clone했다.
clone exit 0, 28MB, 136 tracked files, **`git status` clean**, submodule 없음, LFS 없음,
local HEAD = 원격 tip.

## 3. 현재 tip vs 과거 `7008304b…`

과거 커밋은 clone 안에서 **도달 가능**하며, tip은 정확히 **1 커밋 앞선다**.

```
1caec3a  실기기에서 잡은 카운트/후속 버그 3건 + 인수인계를 문서로 통합  (2026-08-17)
```

변경 7개 파일 중 평가 관련은 `eval_search.py`(+7), `hybrid_server.py`(+32/-…),
`install_real_device_debug.ps1`뿐이다. 전체 patch는 `upstream_eval_diff_from_7008304b.patch`.

**`eval_multiturn.py`·`rag_eval_dataset.jsonl`·`cards_eval1000.json`은 두 커밋 사이에서 변경 없음**이며,
사용자가 제시한 과거 SHA **3개 모두와 바이트 단위로 일치**한다.

## 4. 필수 파일·SHA

| 파일 | 크기 | SHA-256 | 과거 SHA |
|---|---|---|---|
| `scripts/hybrid_server.py` | 77,425 | `5fc3872ddadcc898…a424f0` | (기록 없음) |
| `scripts/eval_multiturn.py` | 38,660 | `26a522235fbbd737…d19031` | **일치** |
| `scripts/eval_search.py` | 66,061 | `af6003fa478af803…7b6f48d` | (기록 없음) |
| `eval/rag_eval_dataset.jsonl` | 1,964 | `73eaf7a7d88362e3…5ce4862` | **일치** |
| `data/cards_eval1000.json` | 405,413 | `f0feaebfdf5eb26c…3e7cd24` | **일치** |
| `data/cards_test50.json` | 24,257 | `29067863ec5eab61…afdb711` | — |
| `data/cards_clean_small.json` | 17,149 | `57edbe448bdeae79…6942616` | — |

전체 136개 파일 SHA manifest는 **실행 전에** `UPSTREAM_MANIFEST.json`으로 생성했다.

## 5. `hybrid_server.py` 의존성

1,434행. 모듈 수준 import: 표준 라이브러리 12개 + `eval_search`(local) + **`numpy`** +
**`sentence_transformers`**(705행).

**`--help` 이전에 무거운 것을 import할 뿐 아니라, 706행이 import 시점에 모델을 즉시 로드한다.**

```
MODEL = SentenceTransformer(str(ev.MODEL_PATH))     # 지연 로딩 아님
```

`eval_search.py`는 모듈 수준이 표준 라이브러리뿐이고 numpy·sentence_transformers를
함수 안에서 지연 import한다.

**`eval_multiturn.py`는 third-party 의존성이 0개다** — argparse/collections/json/pathlib/
random/re/sys/time/urllib뿐. 서버만 있으면 어디서든 돈다.

**requirements 파일이 브랜치에 없다.** 버전 pin을 원본에서 유도할 수 없고, 패키지 **이름**만
import에서 정확히 유도된다.

현재 환경: `numpy` AVAILABLE, `torch` AVAILABLE, **`sentence_transformers` MISSING**,
**`transformers` MISSING**.

## 6. 모델·포트·외부 프로세스

| 리소스 | 기대 | 상태 |
|---|---|---|
| EmbeddingGemma SentenceTransformer 체크포인트 | `<repo>/models/embeddinggemma-300m`, ~1.2GB | **ABSENT** (`.gitignore` 20행 `models/`) |
| 사전 계산 문서 벡터 | `data/cards_eval1000_vectors.bin` | **PRESENT** — 1000×768 float32 |
| LiteRT-LM chat 서버 | `127.0.0.1:9379`, model `gemma4e2b` | **ABSENT** (브랜치에 바이너리·기동 스크립트 없음). `--generate`에서만 필요 |
| 포트 8100 / 9379 | | 둘 다 free, 시도 후에도 free |

> **앱의 `.tflite`(179MB)로 대체할 수 없다.** 서버가 요구하는 것은 HuggingFace
> SentenceTransformer 디렉터리이고 앱이 쓰는 것은 양자화 TFLite다. 형식도 로더도 다르다.
> 임의 대체는 하지 않았다.

## 7. `/chat` 계약

`POST http://127.0.0.1:8100/chat`, timeout 600초.

**Request 6필드**: `question`, `history[{q,a,filter_terms}]`, `focus`, `prev_card_ids`,
`conversation_memory`, `dry_run`.

**evaluator가 실제로 읽는 response 필드 9개**: `route`, `field_filters{name,title,location}`,
`card_ids`(R@5는 `[:5]`), `focus`, `abstained`, `answer`, `hybrid_top`, `gen_ms`,
`conversation_memory`. 나머지 12필드는 읽지 않는다.

`conversation_memory`는 evaluator에게 **불투명**하다 — 검사하지 않고 왕복만 시킨다.

## 8. dataset·시나리오

`cards_eval1000.json`: **1000장**, 중복 ID **0**, 누락 필드 **0**, 13개 필드.

`eval_multiturn.py`는 이 카드에서 `Random(SEED=42)`로 **동적으로** 시나리오를 만든다 —
**130 시나리오 / 377턴 / 21종**, 결정적.

| 대화 길이 | 시나리오 |
|---|---|
| 1턴 | 3 |
| 2턴 | 62 |
| 3–5턴 | 55 |
| 6–10턴 | 10 |
| 11턴+ | 0 |

평균 2.90, 중앙 3, 최대 6. `known_gap` **0개**, `generate_only` **6개**(dry-run에서 미실행).

기대 route 분포: `search` 300, `followup` 28, `context_answer` 9, `empty_result` 9,
`abstain` 8, `filtered_count` 8, `total_count` 1, `self_reference` 1, 미검사 13.
슬롯 검사 턴 288, gold 카드 턴 335, `must` 턴 308, `no_cards` 턴 10.

> `eval/rag_eval_dataset.jsonl`(20건, 키 `id`/`query`/`relevantCardIds`/`type`)은
> **`eval_multiturn.py`가 읽지 않는다.** 검색 평가용이다. 두 입력을 섞으면 안 된다.

## 9. metric 정의

`METRIC_DEFINITIONS.md`에 분자·분모를 코드 기준으로 정리했다. 요약:

* **라우팅** = route 일치 턴 / route 기대가 `None`이 아닌 턴
* **JGA** = 슬롯 집합 **완전 일치** 턴 / slots 선언 턴. 세 축(`names`/`titles`/`locations`)은
  **항상** 비교하고 `focus`는 시나리오가 명시할 때만
* **슬롯 P/R/F1** = tp/fp/fn 누적, partial credit 있음
* **R@5** = gold가 `card_ids[:5]`에 있는 턴 / gold 선언 턴
* **체크리스트** = `--generate`에서만, LLM 생성 턴과 결정적 턴을 **분리 집계**
* **pass^k**, **대화 길이별 성공**, **라우팅 오분류 상위 10**
* **latency** — 원본 주석이 "실기기 지연은 이 도구로 측정할 수 없다"고 못박음

**공식 gate가 코드에 없다.** 결과 파일도 쓰지 않는다(stdout 전용).
**실패가 몇 건이든 `main()`은 0을 반환한다** — `return 1`은 워밍업 연결 실패뿐.
따라서 exit code로 PASS를 판정하면 안 되고 wrapper가 필수다.

## 10. 서버 startup 결과

upstream 원본 명령을 **수정 없이** 실행했다.

```
start 2026-08-22T11:31:12+0900   exit 1
stdout: [hybrid] 카드/벡터/모델 로딩 중… (EmbeddingGemma 1.2GB — 30초~1분 걸릴 수 있음)
stderr: File "…/scripts/hybrid_server.py", line 705, in <module>
        from sentence_transformers import SentenceTransformer
        ModuleNotFoundError: No module named 'sentence_transformers'
```

포트 8100은 시도 후에도 free — 아무것도 남지 않았다. upstream에 `__pycache__` **0개**.
**source를 고쳐 억지로 실행되게 만들지 않았다.**

두 번째 차단 요인이 그 뒤에 대기 중이다: 의존성을 설치해도 706행이 없는 체크포인트를 로드한다.

## 11. `/chat` smoke

**NOT_RUN** — 서버가 뜨지 않아 호출할 엔드포인트가 없었다. 대체 서버를 세우지 않았고
가짜 응답을 만들지 않았다. 계획했던 5개 요청은 `chat_smoke_requests.json`에 그대로 남겼다.

## 12. full dry-run diagnostic

원본 evaluator를 **수정 없이** 실행했다.

```
exit 1
[err] 서버에 연결하지 못했습니다(<urlopen error [Errno 61] Connection refused>).
      python scripts/hybrid_server.py 를 먼저 띄우세요.
```

워밍업 호출(`eval_multiturn.py:619`)에서 멈췄고 **시나리오는 0개 채점됐다.**

이것이 증명하는 것: evaluator는 카드를 읽고 시나리오를 만들고 HTTP 경계까지 **정상 동작**하며
연결 실패 시 깨끗하게 1로 종료한다. 증명하지 않는 것: 라우팅·JGA·R@5 등 **어떤 metric도 아니다.**

## 13. generation smoke

**NOT_RUN.** 허용 조건 6개 중 3개가 불충족(모델 부재, LLM 서버 부재, hybrid 서버 미기동).
명령은 `generation_smoke_result.json`에 기록했다.

## 14. 원본 Ryeong 결과와 현재 Android 에이전트 결과의 구분

`hybrid_server.py`는 자기 헤더에서 **"앱과 동일한 파이프라인을 노트북에서 재현한다"**,
**"eval_search.py의 로직을 재사용한다"**고 밝힌다. **포팅이지 호출이 아니다.**
JVM도 `AgentKernel`도 Room도 띄우지 않는 **Python 평행 구현체**다.

> **원본 서버만 실행한 결과를 현재 Android 에이전트의 멀티턴 성능으로 보고할 수 없다.**

## 15. production adapter — 구현하지 않았다

**결정적 이유**: upstream 계약에 도구 개념이 **아예 없다.**

| 개념 | server | evaluator |
|---|---|---|
| `compose` / `calendar` / `tool_call` / `outcome` | **0** | **0** |

upstream route 8종은 전부 검색 관련이고, production의 `ACTION_COMPOSE`·`ACTION_CALENDAR`·
`ACTION_UPDATE`·`DATETIME_QUERY`·`CORRECTION`·`FAILURE_QUESTION`은 **표현할 자리가 없다.**
typed `TurnOutcomeType`, 순서 있는 tool trace, side-effect admission, 새 대화 폐기,
provenance/stale-ID 차단도 마찬가지다.

upstream 계약만 구현하면 production 계약의 절반 이상이 평가되지 않는데 결과물은
"멀티턴 평가를 통과했다"처럼 보인다. **구현 전에 정책 결정이 필요하다** —
① upstream 부분집합만 평가하고 범위를 항상 명시, ② 저장소의 `MultiturnSpec`/
`StrictMultiturnEvaluator`로 production 전체 계약을 평가하고 upstream에서는 시나리오 다양성만
차용, ③ 둘을 분리된 두 run으로. 상세는 `ADAPTER_TRANSLATION_MATRIX.md`.

self-test 16개는 **0개 실행**이다.

## 16. 아직 필요한 것

1. `models/embeddinggemma-300m` SentenceTransformer 체크포인트 (~1.2GB, HF 게이트 승인 가능성)
2. 격리 venv에 `sentence-transformers`(+`transformers`) 설치 — 버전은 설치 시점에 `pip freeze`로 고정
3. `litert-lm serve` 바이너리 — `--generate`를 하려면
4. **adapter 정책 결정** (§15) — 현재 에이전트 평가를 시작하려면
5. 실기기 — 온디바이스 지연은 이 도구로 측정 불가

## 17. 공식 freeze 가능 여부

**부분 가능.** upstream commit·evaluator·server·dataset SHA, seed, scenario inventory,
environment, exact command은 이미 고정했다. 고정할 수 없는 것: **model artifact SHA**(부재),
**dependency versions**(브랜치에 pin 없음), **adapter SHA**(미구현).
gate는 원본에 없으므로 결과를 보기 전에 사전 등록해야 한다. `OFFICIAL_FREEZE_PLAN.md`.

## 18. 공식 평가 exact command

`official_run_command.txt` — venv 구성, 서버 기동(PID 기록), dry-run 1회, 선택적 생성 모드,
기록한 PID로만 종료. **이번 작업에서 실행하지 않았다.**

## 19. 무결성

| 항목 | 값 |
|---|---|
| 보호 evidence | **778 → 778**, changed 0 / removed 0 / added 0 |
| production·test source (`.kt`/`.java`) | **217 → 217**, changed 0 |
| upstream clone | `git status` **clean**, 파일 수정 **0**, `__pycache__` **0** |
| `../ryeong` | HEAD·branch·dirty 상태 **그대로**, fetch·전환 **없음** |
| run_3 invocation | **1 → 1**, 신규 공식 result 디렉터리 **0** |
| debug APK | SHA **불변** (`e4bfa60a…`) |
| JVM XML | **85개 그대로**, 테스트 실행 **0** |
| 작업 저장소 git 조작 | checkout/reset/pull/merge/rebase/commit/push **0회** |
| 시스템 전역 pip 설치 | **0** |
| 모델 다운로드 | **0** |
| 종료한 프로세스 | **0** (`killall`·`pkill` 미사용) |

모든 Python 호출에 `PYTHONDONTWRITEBYTECODE=1`을 썼다. 사용자의 기존 dirty/untracked 변경
(tracked 25건, untracked 9,914건)은 그대로다.

## 20. 미실행 항목

* `/chat` smoke 5건 — 서버 미기동
* full dry-run 완주 — 서버 미기동 (시도했고 워밍업에서 멈춤)
* generation smoke — 전제 3개 불충족
* production adapter 구현과 self-test 16건 — 계약 불일치로 정책 결정 대기
* 공식 frozen run — 이번 범위 밖
* Gradle·JVM·APK·emulator·실기기·actual Gemma — 전부 미실행

## 21. 지금 가능한 주장과 불가능한 주장

**가능**

* 원격 `llm-integration-work` branch를 실제로 확보했고 tip은 `1caec3a2`다.
* `eval_multiturn.py`와 두 dataset은 과거 SHA와 **바이트 동일**하다.
* evaluator는 third-party 의존성이 없고, `--help` exit 0, 9개 스크립트 전부 AST parse 통과.
* 카드 1000장은 중복 ID 0, 누락 필드 0으로 정상 파싱된다.
* evaluator는 **130 시나리오 / 377턴 / 21종**을 SEED 42로 결정적으로 만든다.
* 서버가 뜨지 못하는 정확한 이유와 순서를 재현된 traceback으로 확정했다.
* 원본에 gate가 없고 실패해도 exit 0이라는 것을 코드로 확인했다 — wrapper가 필수다.
* upstream은 검색 채팅을 평가하며 도구·outcome·side-effect 계약이 **없다**.

**불가능**

* 원본 Ryeong 평가를 재현했다 — **아니다.** 0개 시나리오가 채점됐다.
* 어떤 멀티턴 metric을 측정했다 — **아니다.**
* 현재 Android 에이전트를 평가했다 — **아니다.** adapter가 없고 계약이 맞지 않는다.
* `multiturn-final-20260801.json`의 40/40이 현재 성능이다 — **아니다.** 과거 결과 요약이며
  dataset도 evaluator도 현재 수치도 아니다.
* 검색 run_3 수치를 멀티턴 수치로 쓸 수 있다 — **아니다.**
* production ready — **아니다.**

---

## 22. 질문별 답

| 질문 | 답 |
|---|---|
| 원격 `llm-integration-work` branch를 실제로 확보했는가? | **YES** |
| `hybrid_server.py`를 실제로 읽고 의존성을 추적했는가? | **YES** |
| 원본 Ryeong 서버를 시작할 수 있는가? | **NO** |
| `/chat` endpoint가 실제 응답하는가? | **NO** |
| 원본 `eval_multiturn.py` full dry-run이 실행되는가? | **NO** (워밍업에서 중단, 0 시나리오) |
| generation mode를 실행할 수 있는가? | **NO** |
| 현재 Android production `AgentKernel`을 평가하는가? | **NO** |
| 평가 adapter가 production 경로를 우회하지 않는가? | **BLOCKED** (adapter 미구현) |
| 공식 평가 입력을 freeze할 준비가 됐는가? | **BLOCKED** (model SHA·dependency 버전·adapter SHA 미확정) |
| 지금 즉시 공식 멀티턴 평가를 실행해도 되는가? | **NO** |

**readiness diagnostic에서 종료했고 공식 평가로 넘어가지 않았다.**
