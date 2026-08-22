# Ryeong 검색 통합 평가

## 평가 기준

- Ryeong commit: `0bfd236efa40987c8f0a620d256be9c099080274`
- 현재 Room fixture: `app/src/main/assets/cards/business_cards.json` 2장
- 검색 평가 질의: 10개
  - 정확한 한글/영문 이름 4
  - 회사·직책·부서·지역·태그·메모 keyword 4
  - literal overlap이 없는 의미 질의 2
- latency: 초기화 후 100회, JVM/M5 Pro
- memory: JVM heap pool peak. Android native EmbeddingGemma memory가 아님
- 기존 기준: 통합 직전 `LocalEmbeddingEngine`이 model-backed가 아니어서 실제
  agent가 사용하던 keyword-only adapter
- hybrid 구조 검증: 768차원 deterministic fake. RRF/adapter 검증용이며 실제
  EmbeddingGemma 정확도로 간주하지 않음

## 검색 정확도

| 경로 | Recall@1 | Recall@5 | MRR | exact-name | semantic |
|---|---:|---:|---:|---:|---:|
| 기존 keyword adapter | 0.800 | 0.800 | 0.800 | 1.000 | 0.000 |
| 통합 후 asset-missing fallback | 0.800 | 0.800 | 0.800 | 1.000 | 0.000 |
| deterministic 768d hybrid/RRF | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |

현재 저장소에는 EmbeddingGemma model/tokenizer가 없으므로 실제 production
실행은 두 번째 행이다. 기존보다 정확도가 낮아지지 않았고, model-backed
경로에서는 같은 adapter와 RRF가 semantic-only 정답을 회수하는 것을
검증했다. 실제 EmbeddingGemma semantic 정확도는 asset 준비 후 실기기에서
별도로 측정해야 한다.

중복 이름은 두 Room ID를 모두 유지하며 workflow가 사용자 선택을 요구한다.
없는 검색어는 빈 결과를 반환한다. `topK`, 부분 이름, 영문 이름, 회사,
직책/부서, 산업/지역, 태그/메모, 특수문자 normalization과 PII 미노출을
단위 테스트로 검증했다.

## 성능

동일 evaluator를 cold-ish 단독 실행과 전체 suite 안에서 반복한 관측 범위:

```text
previous_init_ms=0.133..1.224
integrated_init_ms=0.483..6.285
previous_p50_ms=0.069..0.083
previous_p95_ms=0.126..0.155
integrated_p50_ms=0.150..0.161
integrated_p95_ms=0.224..0.244
previous_heap_peak_mb=8.527..14.298
integrated_heap_peak_mb=12.527..16.323
deterministic_hybrid_heap_peak_mb=16.527..19.323
fallback_ratio=1.000
```

2장 fixture의 fallback adapter 비용은 절대값 1ms 미만이다. 증가분은
metadata, Room embedding adapter와 diagnostics 객체를 포함한 JVM
microbenchmark 값이다. 실제 EmbeddingGemma query embedding 시간과 native
peak memory는 asset이 없어 측정하지 못했다.

참고로 upstream commit의 실기기 기록은 Galaxy S8 CPU에서 768차원 한 문장
4,866ms, 5,000장 Room/embedding/FTS DB 약 34MB, 앱 메모리 약 116MB다.
이는 현재 앱에서 재측정한 값이 아니므로 합격 수치로 사용하지 않았다.

## 개인정보와 provenance

- 검색 tool 결과: `card_id`, 이름, 회사, 직함, match summary, RRF/keyword
  score만 반환
- 전화, 휴대전화, 이메일, 주소, website, memo 원문: 검색 tool 결과에 없음
- 상세 PII: 같은 Room `card_id`로 `get_contact`가 성공한 뒤에만 반환
- 내부 diagnostics: final/keyword/semantic rank, source, similarity, fallback
  reason, engine, initialization/query embedding/total latency
- model/차원/source hash가 다른 embedding은 재사용하지 않음
- fallback 결과도 session의 `last_search_results.card_ids`에 기록되어 기존
  provenance validator를 그대로 통과해야 함

## 검색·통합 테스트

검증 항목:

- exact/partial/English name
- company/title/department/industry/location/tag/memo
- keyword-only, semantic-only, 양쪽 일치와 RRF
- 중복 이름, 결과 없음, top-k, stable Room ID
- email/phone normalization 후 검색 payload PII 차단
- RAG context의 phone/email 비포함
- model embedding 성공과 Room persistence
- model/tokenizer 누락, 초기화/inference 실패, dimension mismatch fallback
- `search_contacts → get_contact`
- 연락처 기반 이메일/문자/일정/수정의 기존 controller sequence
- 검색 0/1/다수, 상세 destination 누락, fallback workflow
- unsupported/unsafe 실행과 거짓 완료 방지

최종 전체 JVM 결과는 70 tests, failures 0, errors 0, skipped 0이다.
검색 전용은 `SearchLookupServiceTest` 9개와 contact plugin/evaluator 8개다.

## Staged agent 64개 전후

모델, SHA-256, LiteRT-LM 0.14 CPU, test case와 기대값을 동일하게 사용했다.

| 지표 | 통합 전 | 통합 후 |
|---|---:|---:|
| final intent | 100.0% (64/64) | 100.0% (64/64) |
| action | 93.8% (60/64) | 93.8% (60/64) |
| required slot | 97.4% (38/39) | 97.4% (38/39) |
| workflow | 97.5% (39/40) | 97.5% (39/40) |
| multi-tool | 100.0% (15/15) | 100.0% (15/15) |
| contact chain | 100.0% (11/11) | 100.0% (11/11) |
| schema | 100.0% (64/64) | 100.0% (64/64) |
| strict | 84.4% (54/64) | 84.4% (54/64) |
| unsafe 실행 | 0 | 0 |
| 거짓 완료 | 0 | 0 |

본문 output은 전후 byte-equivalent였으므로 기존 수동 rating을 재사용할 수
있었고 7.8/10으로 동일하다. 검색 통합으로 staged agent 회귀는 없다.

결과:

- raw: `tools/litertlm_benchmark/results/gemma4-staged-intent-e-64-ryeong-search.jsonl`
- report: `tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-ryeong-search_report.md`
- CSV: `tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-ryeong-search_evaluation.csv`

## Android build와 남은 실기기 gate

- `./gradlew test :app:assembleDebug`: 성공
- debug APK: `app/build/outputs/apk/debug/app-debug.apk` (132MB)
- RAG SDK와 LiteRT-LM 0.13.1 duplicate class/native packaging: 성공
- EmbeddingGemma native library는 arm64-v8a이고 AVD x86_64에서는
  keyword fallback을 사용한다.

실제 hybrid gate에 필요한 파일:

```text
<internal files>/models/embeddinggemma-300m.tflite
<internal files>/models/sentencepiece.model
```

또는:

```text
<external files>/models/embeddinggemma-300m.tflite
<external files>/models/sentencepiece.model
```

두 파일을 제공한 arm64 실제 기기에서 다음을 추가 측정해야 한다.

1. model initialization과 768차원 query/document embedding
2. 실제 semantic Recall@1/5와 MRR
3. query embedding p50/p95와 native peak RSS
4. Room vector 재시작 재사용과 수정 card 재색인
5. 실제 `search_contacts → get_contact → open_compose` end-to-end

파일을 새로 다운로드하거나 저장소에 복사하지 말라는 제한 때문에 이
실기기 model-backed gate만 blocker로 남았다. asset이 없는 현재 APK는
crash하지 않고 명시적 keyword fallback으로 정상 동작한다.
