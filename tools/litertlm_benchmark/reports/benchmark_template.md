# LiteRT-LM 벤치마크 보고서: `<model-name>`

## 실행 정보

- 모델 경로:
- 모델 SHA-256:
- LiteRT-LM CLI 버전:
- backend:
- 실행 시각:
- 결과 JSONL:
- 평가 CSV:

## 자동 평가

| 항목 | 결과 |
|---|---:|
| 프로세스 실행 성공률 | |
| native `[tool_call]` 출력률 | |
| tool 선택 정확도 | |
| 필수 argument 정확도 | |
| JSON/schema 파싱 성공률 | |
| no-tool 정확도 | |
| multi-tool chain 완료율 | |
| 존재하지 않는 tool 생성 횟수 | |
| 거짓 완료 표현 횟수 | |
| 비어 있는 body 횟수 | |
| body 요구 키워드 반영률 | |

## 사람 평가(0~2점)

| 테스트 ID | 한국어 자연스러움 | 채널 적합성 | 메모 |
|---|---:|---:|---|
| | | | |

## 실행 오류 원문

실패 케이스가 있으면 실행기가 보존한 stdout과 stderr 원문을 여기에 기록한다.

native tool call은 LiteRT-LM CLI stdout의 `[tool_call]` event가 파싱될 때만 성공으로 판정한다.
