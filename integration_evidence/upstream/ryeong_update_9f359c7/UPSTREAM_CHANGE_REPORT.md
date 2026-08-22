# Ryeong `llm-integration-work` 업데이트 반영 — `1caec3a2` → `9f359c7`

원격이 6 커밋 앞섰다. **24개 파일, +10,147 / −143 행.**

```
9f359c7 발표용 지표 문서 — 검색/멀티턴/고정셋/실기기로 나눠 정리
7493300 지표 자체 감사 — 한계를 출력이 스스로 말하게 한다
1b63f5b 복합 속성어를 두 칸으로 쪼개지 않는다
abcf333 평가 발화 다양성 + 군말 붙은 생략형 후속 처리 + 팀원 고정셋 레포 반영
d7c27bc 여러 칸 질문을 코드가 조합해 답한다 (47/50)
11f2791 멀티턴 결함 5개 수정 + 기능 안내 우회 + 지표 정명
```

---

## 1. 멀티턴 성능 평가 — **바뀌었다**

### 1.1 evaluator 자체

| 파일 | 이전 | 현재 |
|---|---|---|
| `scripts/eval_multiturn.py` | `26a52223…d19031` | **`8d69896d…332a95`** |
| `data/cards_eval1000.json` | `f0feaebf…3e7cd24` | **동일 (불변)** |
| `eval/rag_eval_dataset.jsonl` | `73eaf7a7…ce4862` | **동일 (불변)** |

**dataset은 그대로이고 시나리오 생성기만 바뀌었다.**

신규 로컬 의존성이 생겼다 — `scripts/card_fingerprint.py`(`ba58c83c…`)를 `import card_fingerprint as _cfp`로
가져오고(54행) 실행 시작 시 `data/cards_embeddings_fingerprint.json`으로 카드 지문을 검증한다(757~759행).
v4 exporter는 `scripts/`를 import 경로에 넣지 않아 그대로는 **import 실패**한다.

### 1.2 시나리오 inventory

`SEED=42`, `PYTHONHASHSEED=0`, 독립 프로세스 4회 byte-identical 재확인.

| 항목 | v1 (`1caec3a2`) | v2 (`9f359c7`) | 델타 |
|---|---|---|---|
| scenarios | 130 | **139** | +9 |
| turns | 377 | **386** | +9 |
| kinds | 21 | **22** | +1 |
| known_gap | 0 | 0 | — |
| generate_only | 6 | 6 | — |
| 1턴 | 3 | **12** | +9 |
| 2턴 / 3–5턴 / 6–10턴 | 62 / 55 / 10 | 62 / 55 / 10 | — |
| export SHA | `c5c23888…` (284,097 B) | **`4c169c19…`** (294,103 B) | — |

늘어난 9개는 전부 1턴 카운트 계열이다 — `전체 카운트` **1 → 6**(문구 변형), 신규 kind
**`조사 변형 카운트` 4개**.

### 1.3 route 분모 — **scorable은 그대로, excluded만 늘었다**

| route | v1 | v2 |
|---|---|---|
| `search` | 300 | **300** |
| `followup` | 28 | **28** |
| (미주장) | 13 | 13 |
| `filtered_count` | 8 | **12** |
| `total_count` | 1 | **6** |
| `empty_result` / `context_answer` / `abstain` / `self_reference` | 9 / 9 / 8 / 1 | 동일 |
| **routing scorable** | **328** | **328** |
| **routing excluded** | **49** | **58** |
| 합계 | 377 | 386 |

우리 translation contract가 `DERIVED_FROM_PRODUCTION_TRACE`로 잡은 두 route(`search`, `followup`)의
분모는 **정확히 그대로다.** 늘어난 9턴은 전부 이미 `NOT_SCORABLE`인 카운트 계열이다.

gold 선언 턴(R@5 상한): 335 → **339**. slot 선언 턴: 288 → **288**(불변).

### 1.4 발화가 대량으로 재작성됐다

`add(...)`의 고정 문자열이 `pick(rng, ASK_COMPANY, n=...)`·`ELLIPTIC[...]` 풀에서 뽑는 방식으로 바뀌었다.

```
v1[0][0]  현수씨 회사가 어디야?
v2[0][0]  현수씨 회사 알려줘
```

**같은 index에서 질문 목록이 동일한 시나리오는 130개 중 28개뿐이다.** 102개가 재작성됐다.

### 1.5 metric 정의 변경

`new_stats()` 키가 바뀌었다.

| v1 | v2 |
|---|---|
| `r5_ok` / `r5_n` (이진 적중) | `hit5_ok` / `hit5_n` (이진 Hit@5) |
| — | **`r5_sum`** — 등급 recall: `len(found) / min(len(gold), 5)` |
| — | **`mrr_sum`** — MRR 신규 |

`--seed` CLI 인자가 추가돼 seed를 바꿔 돌릴 수 있고, `ceiling()`이 각 지표 옆에 상한을 같이 찍는
**지표 자체 감사**가 들어갔다. `GENERATING_ROUTES`는 `{search, followup, empty_result}`로 동일하다.

### 1.6 신규 — Final50 v3 고정 challenge set

`benchmark_final50_v3/` 4개 파일이 새로 들어왔다.

* `business_card_multiturn_benchmark_final50_v3.json` — 50 시나리오 / **244 user turn** / 평가 대상 turn 50 / 5개 범주
* `..._annotations.json`, `README_final50_v3.md`, `eval_benchmark_final50_v3.py`(별도 evaluator, F1·EM 채점)
* 기준 카드는 동일한 `cards_eval1000.json` 1,000장

README가 명시한다 — **"Final50 v3의 고정 challenge set과 동적 regression suite는 서로 목적이 다르므로
하나의 overall score로 합치지 않는다."** 우리 쪽에서도 `eval_multiturn`(동적 139)과 Final50 v3(고정 50)을
합치면 안 된다.

---

## 2. 명함 검색·조회 도구 — **바뀌었다**

ryeong 앱 쪽 Kotlin 4개 파일이 실측 결함을 근거로 수정됐다.

| 파일 | 변경 |
|---|---|
| `KeywordSearchRanker.kt` | 계사 종결형 **`"이야"`·`"야"`**를 조사 목록에 추가. `"손서윤씨가 아니라 남다은씨야"`에서 `남다은씨야`가 이름으로 안 잡혀 **정정이 통째로 무시**됐다(실측 Final50 v3 정정 6/10 실패). 추가로 **문장 끝 구두점**(`.`/`_`/`-`)을 떼고 다시 조사 제거를 시도 — `"남다은씨야."`는 이름 추출 0건이었다 |
| `CardGazetteer.kt` | **`namedRealPerson` 가드 신설.** 질의가 데이터에 실재하는 사람을 이미 지목했으면, 곁가지 토큰의 지명 오탐으로 전체를 기권시키지 않는다. `"두미영 회사와 이메일도 알려줘"`가 `이메일도`를 '도(道)로 끝나는 3자 이상'으로 읽어 **기권**했다 |
| `CardSearchService.kt` | **`knownNamesIn(query)` 신설** — 검색과 같은 어휘로 실재 인물명을 뽑아 대상 정정에 쓴다 |
| `ToolRegistry.kt` | **`capabilityLabels()` 신설** — `"뭐 할 줄 알아?"` 답변을 도구 declaration에서 파생 |
| `ConversationalFollowup.kt` | **`PERSON_DEIXIS` / `pointsAtPerson()` 신설.** `"아까 그 사람 회사 알려줘"`가 되짚기로 잡혀 직전 답변을 재생했다(sc_03·sc_08·lr_04·er_04). 사람을 가리키면 되짚기가 아니라 **새 질문**으로 본다 |
| `AgentSession.kt` | **`KEY_SUBJECT_HISTORY` 신설** — 화제 인물을 처음 나온 순서대로 누적. `recentMessages`는 4턴 창이라 `"처음에 물어본 사람 전화번호는?"`에 닿지 못했다 |

### 우리 production에 대한 의미

이 6개 클래스는 **ryeong 앱 계층의 것이고 우리 저장소에 대응 클래스가 없다.** 확인 결과
`stripParticle`·`CardGazetteer`·`KeywordSearchRanker`·`looksLikeRegion` 어느 이름도 우리 쪽에 없다.
우리는 `search-core`의 `SearchFieldConstraintResolver` + `SearchFieldVocabulary`라는 **다른 구현**을 쓴다.

다만 **같은 종류의 기권 로직이 우리 쪽에도 있다.**

```java
// SearchFieldConstraintResolver.java:98-105
if (!locations.isEmpty() && !known) {
    abstainReason = "NO_CARD_IN_REQUESTED_LOCATION";
}
// :58  private static final String PROVINCE_SUFFIX = "도";
// :131 roleMarked && token.endsWith(PROVINCE_SUFFIX) && ...
```

ryeong이 방금 막은 것과 **같은 모양의 오탐 경로**다. 차이는 우리 쪽 `도` 규칙이 `roleMarked`
(질의에 지역 역할 표지가 있을 때)로 한 번 더 좁혀져 있고 `nonLocationTerms` 제외 목록이 있다는 것이다.
그러나 **"질의가 실재 인물을 지목했으면 기권하지 않는다"는 가드는 우리에게 없다**
(`nameExists`/`personName`/`namedRealPerson` 참조 **0건**).

> 이번 작업에서 이 production 코드를 고치지 않았다. 평가 결과나 upstream 변경을 근거로 production을
> 조용히 바꾸지 않는다는 원칙에 따라, 아래 §4에 red characterization 후보로 남긴다.

---

## 3. 에이전트 성능 평가 — **바뀌었다**

* `eval_search.py` — `9e153102…` → `af176214…`(+42행). 검색 축 평가기.
* `hybrid_server.py` — `f0655e0e…` → `471c73b9…`(+307행). 로컬 재현 서버. 우리는 이 서버를
  **쓰지 않는다**(여전히 `sentence_transformers`·1.2GB 체크포인트 부재로 BLOCKED, 이번 변경과 무관).
* `scripts/card_fingerprint.py`(신규) + `data/cards_embeddings_fingerprint.json`(신규) — 카드 데이터와
  사전 계산 임베딩의 **지문 검증**. 평가 시작 시 데이터가 바뀌었는지 스스로 알린다.
* `MainActivityTest.kt` +287행, `CardGazetteerTest.kt`·`KeywordSearchRankerTest.kt` 신규 — 위 검색 수정의 회귀 테스트.
* `docs/성능지표.md` / `.html` 신규 — 검색·멀티턴·고정셋·실기기로 축을 나눈 발표용 지표 문서.

---

## 4. 무엇을 반영했고 무엇을 하지 않았나

### 반영한 것

1. 격리 clone에 `fetch` — local HEAD는 **여전히 `1caec3a2`**(봉인 유지), `FETCH_HEAD`가 `9f359c7`.
2. `tools/ryeong_multiturn_v5/` 신설 — 새 tip의 `eval_multiturn.py`·`card_fingerprint.py`·
   `eval_search.py`·`cards_eval1000.json`·`cards_embeddings_fingerprint.json`을 **바이트 동일** 사본으로 보존.
3. `export_scenarios.py` v5 — `scripts/`를 import 경로에 추가(신규 의존성 대응), SHA·commit·
   exact gate를 새 값으로 갱신. **`PYTHONHASHSEED=0` 고정은 그대로 유효.**
4. `generated/ryeong_multiturn_scenarios_v2.json` — 139/386/22, SHA `4c169c19…`,
   독립 프로세스 4회 byte-identical, exact gate 전부 통과.

### 하지 않은 것

* **봉인된 v1을 건드리지 않았다.** `scenarios_v1.json`(`c5c23888…`), `cards_eval1000.json`,
  `ryeong_official_v1/` 결과·freeze·보고서 전부 불변.
* **RUN_K1을 다시 돌리지 않았다.** v1 공식 실행은 `INVALID RUN`으로 봉인된 상태 그대로다.
* **production을 수정하지 않았다** — `src/main` 변경 0건.
* **adapter의 frozen 상수를 바꾸지 않았다.** `RyeongOfficialCompatibilityRunTest`는 여전히 v1
  SHA와 328/49를 요구한다. 여기를 v2로 올리면 봉인된 v1 실행과 계약이 어긋난다.

---

## 5. 다음에 결정해야 할 것

1. **v2로 공식 축을 올릴지.** 올린다면 `RyeongScenarioLoader`의 기대 인벤토리(130/377/21),
   `RyeongOfficialCompatibilityRunTest`의 frozen SHA·분모(328/49)를 v2 값(139/386/22, 328/**58**)으로
   갱신하고 **새 version의 공식 run**으로 실행해야 한다. v1 결과와 합치지 않는다.
   routing scorable 분모가 328로 동일하므로 **routing 축은 v1과 직접 비교 가능**하다.
2. **발화 재작성이 우리 결과를 바꿀 수 있다.** v1 공식 실행에서 `search_contacts` 호출이 0회였던
   원인은 문구 계약 불일치였다. v2에서 102개 시나리오 문구가 바뀌었으므로 **다시 측정할 가치가 있다** —
   다만 이는 v2 공식 run에서 확인할 사안이고, 이번에 추정치를 만들지 않는다.
3. **`NO_CARD_IN_REQUESTED_LOCATION` 오탐.** ryeong이 고친 것과 같은 모양의 경로가 우리
   `SearchFieldConstraintResolver`에 있다. `"… 이메일도 알려줘"` 류가 기권으로 떨어지는지
   **red characterization**으로 먼저 재현할 것.
4. **Final50 v3 도입 여부.** 고정 50 challenge set은 동적 suite와 목적이 다르다. 도입하면
   별도 축·별도 보고서로 두고 overall score로 합치지 않는다(upstream README의 명시적 지침).
5. **`hybrid_server.py` 축은 여전히 BLOCKED** — `sentence_transformers` 부재와 1.2GB 체크포인트
   부재는 이번 업데이트로 해소되지 않았다.
