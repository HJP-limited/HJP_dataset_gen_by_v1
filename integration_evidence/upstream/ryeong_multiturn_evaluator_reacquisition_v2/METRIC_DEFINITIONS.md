# 원본 `eval_multiturn.py` metric 정의 (코드 기준)

출처: `scripts/eval_multiturn.py` @ `1caec3a2` (SHA `26a52223…d19031`, `7008304b`와 **바이트 동일**).
모든 분자·분모는 `new_stats()`(584행), `run_pass()`(436~540행), 출력부(642~700행)에서 읽었다.

## 공통

* **rounding은 출력 시점에만** 일어난다 — `f"{a/b:.3f}"`. 내부 누적은 정수 카운터다.
* **분모가 0이면** `"  -  "`를 출력한다. 0으로 나누지 않는다.
* `known_gap` 시나리오는 `stats["gap"]`이라는 **별도 stats 객체**에 누적되어 headline에서 완전히 빠진다.
  현재 tip 기준 `known_gap` 시나리오는 **0개**다.
* `generate_only` 시나리오(**6개**)는 dry-run에서 `continue`로 **아예 실행되지 않는다**(440행).
* 시나리오 순서·구성은 `random.Random(SEED=42)`로 **결정적**이다.

## 1~3층 — 생성 불필요 (dry-run에서 채점됨)

| metric | 분자 | 분모 | 제외 조건 |
|---|---|---|---|
| **라우팅 정확도** | `res["route"] == t["route"]`인 턴 | `t["route"] is not None`인 턴 | 기대 route가 `None`인 턴(13개)은 분모에서 빠진다 |
| **JGA** | `exp == act` (집합 **완전 일치**)인 턴 | `t["slots"] is not None`인 턴 (288) | slots 미선언 턴 |
| **슬롯 P/R/F1** | `tp=|exp∩act|` 누적 | `P=tp/(tp+fp)`, `R=tp/(tp+fn)` | JGA와 같은 턴 집합. partial credit **있음** |
| **턴별 R@5** | gold 중 하나라도 `card_ids[:5]`에 있는 턴 | `t["gold"]`가 비어있지 않은 턴 (335) | gold 미선언 턴 |

**슬롯 집합 구성**(`slot_sets`, 404행): 기대는 `names`/`titles`/`locations`,
실제는 응답 `field_filters`의 `name`/`title`/`location`. 이 세 축은 **항상** 비교한다 —
"안 적었으면 비어 있어야 한다"는 뜻이므로 엉뚱한 조건이 붙는 것도 실패다.
`focus`는 시나리오가 `"focus"` 키를 **명시했을 때만** 비교한다.

JGA는 하나만 틀려도 0이고, 그 부분 점수를 보려고 F1을 같이 낸다.

## 4~5층 — `--generate`에서만

| metric | 분자 | 분모 | 비고 |
|---|---|---|---|
| **체크리스트(LLM 턴)** | `check_answer` 통과 | `route ∈ {search, followup, empty_result}` **이고** `abstained`가 false인, `must`가 있는 턴 | **주 지표** |
| **체크리스트(결정적 턴)** | 같음 | 위 조건을 만족하지 **않는**, `must`가 있는 턴 | 고정 문자열이라 라우팅만 맞으면 통과 |
| **무관 요청 카드 억제** | `card_ids`가 비어 있음 | `t["no_cards"]`인 턴 (10) | 생성 모드에서만 판정 가능 |

`check_answer`(425행): `must`의 각 원소는 **대안 목록**이라 하나만 맞으면 통과.
`must_not`은 등장하면 즉시 실패. 부분 점수 없음.

## 시나리오 단위

| metric | 분자 | 분모 |
|---|---|---|
| **pass^k** (`--repeat k>1`) | k회 **모두** 통과한 시나리오. `known_gap`은 통과로 계산 | 전체 시나리오 수 |
| **대화 길이별 성공** | 그 시나리오의 **모든 턴** 라우팅이 맞은 경우 | 같은 턴 수의 시나리오 수 |
| **라우팅 오분류** | `Counter[(기대, 실제)]` 상위 10건 | — |

## latency — 지표가 아니다

`--latency`에서만 출력하며 `gen_ms`의 중앙/평균/P95/최대를 낸다.
**원본 주석이 직접 못박는다**: 이 값은 9379 포트 LLM 서버로 HTTP 왕복하는 시간이라
**그 서버를 띄운 데스크톱 성능**이고, 온디바이스 실기기 지연은 **이 도구로 측정할 수 없다**.
같은 환경 안에서의 회귀 감지용으로만 쓴다. 기본 출력에서 빠져 있는 이유가 그것이다.

## 공식 gate — 없음

**코드 어디에도 threshold 비교가 없다.** 백분율을 출력할 뿐이다.

* 결과를 파일로 쓰지 않는다 — `json.dump`·`open(...,"w")`·`write_text` **0회**. stdout 전용.
* `main()`은 **실패가 몇 건이든 `return 0`**이다. `return 1`은 워밍업 연결 실패 한 경우뿐이다(620행).
* 따라서 **exit code로 PASS를 판정하면 안 된다.** wrapper가 stdout의 실패 수와 분모를 독립 파싱해야 한다.

## 입력 혼동 금지

`eval/rag_eval_dataset.jsonl`은 **20건**이고 키는 `id`/`query`/`relevantCardIds`/`type`이다 —
`eval_multiturn.py`는 이 파일을 **읽지 않는다**. 멀티턴 시나리오는
`data/cards_eval1000.json`(1000장)에서 `Random(42)`로 **동적으로 130개**를 만든다.
두 입력을 같은 표에 섞으면 안 된다.
