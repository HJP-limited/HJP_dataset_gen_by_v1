# JGA·slot 관측성 최종 조사

**결론: `NOT_SCORABLE` 유지.** production 결과를 바꾸지 않고 관측할 방법을 찾지 못했다.

## 조사한 것

| 대상 | 확인 결과 |
|---|---|
| `search_contacts` input | `SearchContactsInput(query: String, limit: Int)` — **자유 텍스트 하나뿐**. name/title/location 인자가 없다 (`ContactPlugins.kt:31,53-58`) |
| `QueryAnalysis` | public이지만 `rawQuery`·`normalizedQuery`·`keywordQuery`·`semanticQuery`·`tokens`만 노출. **필드별 조건이 없다** |
| `SearchFieldConstraintPlan` | `final class`(package-private). 필드 `locations`·`titles`도 package-private (`SearchFieldConstraintPlan.java:21,29,32`) |
| `SearchLookupService` | **92행에 `SearchFieldConstraintPlan fieldConstraintPlan(String rawQuery)`가 이미 있다** — 단 package-private |
| `retrieve()` | 79행에서 `plan`을 만들어 82~83행에서 적용. plan을 **응답에 담지 않는다** |
| `RetrievalResponse` | plan을 노출하지 않는다 |
| 기존 diagnostic observer | `RyeongContactSearchBackend`의 `SearchDiagnostics`는 순위·모드·시간은 주지만 **field constraint는 주지 않는다** |
| `TracingContactBackend` | backend 밖에서 감싸므로 내부 plan을 볼 수 없다 |

## 우선순위별 판정

1. **기존 trace에서 관측** — 불가. plan이 어떤 응답에도 실리지 않는다.
2. **같은 package에 test 배치** — 불가. adapter는 `app` 모듈이고 plan은 `search-core`의 package-private다.
   Kotlin은 Java package-private 멤버에 대한 안정적인 교차 모듈 접근을 보장하지 않는다.
3. **evaluation-only read-only observer** — `search-core` `src/main` 변경이 필요하다.
4. **default no-op observer seam** — 마찬가지로 `src/main` 변경이 필요하다.
5. → **`NOT_SCORABLE` 유지.**

## 3·4번을 이번 작업에서 하지 않은 이유

`fieldConstraintPlan(rawQuery)`는 **적용된 plan을 보고하는 것이 아니라 raw query로부터 다시 해석한다.**
그대로 public으로 노출하면 "그 턴에 실제로 적용된 조건"이 아니라 "같은 문자열을 다시 해석한 결과"를
관측하게 된다. 결정적 함수라 값은 같겠지만, **관측 대상이 다르다.**

제대로 하려면 `retrieve()`가 자신이 적용한 plan을 진단 콜백으로 내보내야 하고, 그것은
`SearchLookupService`·`RetrievalResponse`·`RyeongContactSearchBackend`를 잇는 production 변경이다.
§6이 요구하는 "observer 추가 전후 production search 결과가 byte/structurally identical"
characterization까지 포함하면 **별도 version의 작업**이다.

따라서 이번 v1에서는 JGA와 slot P/R/F1을 `NOT_SCORABLE`로 두고, 그 사유를 freeze manifest에 남긴다.

## 하지 않은 것

* 최종 응답 문자열에서 slot을 역추론하지 않았다.
* 빈 집합으로 채워 채점하지 않았다.
* production 동작을 바꾸지 않았다 — `src/main` 변경 **0건**.

## 다음 version이 해야 할 일

`SearchLookupService.retrieve()`에 **적용된** plan을 내보내는 no-op 기본값 콜백을 추가하고,
`RetrievalResponse` 또는 `SearchDiagnostics`로 전달한 뒤, observer 유무에 따라 순위·후보·점수가
구조적으로 동일함을 characterization test로 고정한다. 그 뒤에야 §6이 나열한 8개 slot mutation을
수행할 수 있다.
