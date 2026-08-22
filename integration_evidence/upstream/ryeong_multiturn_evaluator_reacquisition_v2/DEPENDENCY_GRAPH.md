# 의존성 그래프 (import 추적 결과)

## `scripts/eval_multiturn.py` — 724행

```
eval_multiturn.py
├── argparse, collections, json, pathlib, random, re, sys, time, urllib  [전부 표준 라이브러리]
└── (외부 패키지 0개, repository-local import 0개)
        │
        └── HTTP POST → http://127.0.0.1:8100/chat        ← 유일한 외부 경계
```

**third-party 의존성이 하나도 없다.** `__main__` 가드가 있고 import 시 부작용이 없다.
서버만 있으면 어떤 Python 3 환경에서도 돈다.

## `scripts/hybrid_server.py` — 1,434행

```
hybrid_server.py
├── json, os, re, sqlite3, struct, sys, threading, time, urllib.request, uuid,
│   http.server, pathlib                                   [표준 라이브러리]
├── eval_search  (line 21, sys.path.insert 후)             [repository-local]
│     └── 모듈 수준은 표준 라이브러리만.
│         numpy(1111행)·sentence_transformers(1126행)는 함수 안에서 지연 import.
│         MODEL_PATH = <repo>/models/embeddinggemma-300m   (37행)
├── numpy                             (line 23, 모듈 수준)  [AVAILABLE]
└── sentence_transformers             (line 705, 모듈 수준) [MISSING ← 실행 차단]
      └── line 706: MODEL = SentenceTransformer(str(ev.MODEL_PATH))
            ← import 시점에 모델을 즉시 로드한다. 지연 로딩이 아니다.
```

런타임 외부 프로세스:

```
hybrid_server (:8100)  ──HTTP──▶  litert-lm serve (:9379)  [--generate 에서만]
                                   model id "gemma4e2b"
                                   ← 브랜치에 바이너리·기동 스크립트 없음
```

## 모듈 수준 실행 부작용

`hybrid_server.py`를 **import만 해도** 다음이 실행된다.

1. 704행 배너 출력
2. 705~706행 SentenceTransformer 로드 (~1.2GB)
3. 708행~ 카드/벡터 로딩, FTS 인덱스 구성

포트 바인딩은 `__main__` 안에서만 일어나므로, import 실패는 포트를 남기지 않는다
(실측 확인: 시도 후 8100 free).

## requirements

**존재하지 않는다.** `requirements.txt`·`pyproject.toml`·`Pipfile`·lock·`environment.yml`
어느 것도 브랜치에 없다. 따라서 **버전 pinning을 원본에서 유도할 수 없다.**
패키지 **이름**만 import에서 정확히 유도된다 — `numpy`, `sentence-transformers`.
