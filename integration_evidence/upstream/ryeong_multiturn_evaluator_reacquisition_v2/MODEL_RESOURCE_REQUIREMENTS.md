# 모델·리소스 요구사항

## 1. EmbeddingGemma 300M — SentenceTransformer 체크포인트 (**차단 요인**)

| 항목 | 값 |
|---|---|
| 기대 경로 | `<repo>/models/embeddinggemma-300m` |
| 선언 위치 | `eval_search.py:37` `MODEL_PATH`, 로드는 `hybrid_server.py:706` |
| 현재 상태 | **ABSENT** |
| gitignore | `.gitignore` 20행 `models/` — 저장소에 넣지 않는 것이 설계 |
| 크기 | ~1.2GB (`hybrid_server.py:704` 배너, `MODEL_SETUP_STATUS.md`의 `model.safetensors 1.21GB`) |
| 출처 | `litert-community/embeddinggemma-300m` (HuggingFace) |

> **앱의 `.tflite`로 대체할 수 없다.** 현재 저장소가 APK에 넣는
> `embeddinggemma-300m.tflite`(179,131,736 B)는 **양자화된 TFLite 변형**이고,
> 서버가 요구하는 것은 **HuggingFace SentenceTransformer 디렉터리**다.
> 로더도 형식도 다르다. 임의 대체는 금지사항이며 하지 않았다.

## 2. 사전 계산 문서 벡터 — 준비됨

`data/cards_eval1000_vectors.bin` — 3,072,000 B = **1000장 × 768 float32**.
문서 쪽은 저장소에 들어 있다. **질의 임베딩만** 런타임에 체크포인트를 필요로 한다.

## 3. LiteRT-LM chat 서버 — `--generate` 전용

`GEMMA_URL = http://127.0.0.1:9379/v1/chat/completions`, `GEMMA_MODEL = "gemma4e2b"`.
`hybrid_server.py:5`가 "먼저 litert-lm serve 가 9379에 떠 있어야 함"이라고 밝히지만
**브랜치에 그 바이너리도 기동 스크립트도 없다.** dry-run에는 필요 없다.

## 4. 포트

| 포트 | 용도 | 점검 시각 상태 |
|---|---|---|
| 8100 | hybrid_server | free (시도 후에도 free) |
| 9379 | litert-lm serve | free |

**어떤 프로세스도 종료하지 않았다.** `killall`·`pkill` 사용 0회.

## 5. 환경

Python 3.9.6 (`/usr/bin/python3`). `numpy` AVAILABLE, `torch` AVAILABLE,
`sentence_transformers` MISSING, `transformers` MISSING.

## 6. 아직 필요한 것

1. `models/embeddinggemma-300m` SentenceTransformer 체크포인트 (~1.2GB) — HF 게이트 승인이 필요할 수 있음
2. `sentence-transformers`(+`transformers`) 설치 — 격리 venv에
3. `litert-lm serve` 바이너리 — `--generate`를 하려면
