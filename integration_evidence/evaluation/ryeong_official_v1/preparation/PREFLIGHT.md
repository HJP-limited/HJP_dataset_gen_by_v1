# PREFLIGHT

기록: 2026-08-22 (KST). 원본: `current_repo_status_before.txt`.

* 경로 `/Users/byeol/Desktop/by/4-1/휴먼 캡스톤/에이전트_FunctionGemma_0723/sojung`
* HEAD `993a5f5613235c2192f6c34b06841319813aefeb`, branch `android-app`
* tracked 25건 / untracked 10,044건 — 전부 작업 전부터 존재. 삭제·되돌림 없음.
* `AGENTS.md` 없음. `CLAUDE.md` 적용.
* ADB: 물리 기기 1대(`R5CY54CP83R`) + emulator 1대. emulator는 §8이 배제.
* 기존 공식 run invocation: **0**

## 선행 evidence 검증

* `ryeong_multiturn_evaluator_reacquisition_v2` — MANIFEST 46개 artifact **전부 SHA 일치**
* `ryeong_to_production_multiturn_adapter_v1` — MANIFEST 37개 artifact **전부 SHA 일치**
* 요구된 계약 파일 전부 존재
* frozen input 3종(`eval_multiturn.py`, `cards_eval1000.json`, exported scenarios) **전부 일치**

SHA mismatch가 없으므로 official run으로 진행했다.
