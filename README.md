# korean_bizcard_full_dataset_rebuild_v2

`korean_bizcard_full_dataset_rebuild_v2`는 한국어 명함 synthetic dataset을 다시 설계한 v2 생성기이다. 목표는 단순 이미지 생성이 아니라, 하나의 마스터 어노테이션을 기준으로 탐지, 인식, KIE까지 바로 사용할 수 있는 파생 산출물을 함께 만드는 것이다.

이 버전의 핵심 특징은 다음과 같다.

- 가로/세로 명함을 분리된 레이아웃 규칙으로 생성한다.
- 생성 단계에서 augmentation을 하지 않아 bbox와 렌더링 텍스트의 정합성을 유지한다.
- `master_annotations`를 1차 정답으로 두고 YOLO, COCO, PaddleOCR 스타일 산출물을 파생한다.
- 실패 샘플 로그, 실행 요약, 검증 요약을 함께 남긴다.
- split은 요청 개수가 아니라 실제 성공한 샘플 ID를 기준으로 만든다.

## 프로젝트 구조

```text
korean_bizcard_full_dataset_rebuild_v2/
├─ README.md
├─ REBUILD_PLAN.md
├─ configs/
│  ├─ dataset_config.json
│  ├─ themes.json
│  ├─ fonts.json
│  └─ company_domain_map.json
├─ docs/
│  └─ validation_report.md
├─ src/
│  ├─ generate_dataset_v2.py
│  └─ qa_checks.py
└─ output/
   └─ <output-name>/
      ├─ images/
      ├─ labels_yolo/
      ├─ coco/
      ├─ master_annotations/
      ├─ det/
      ├─ rec/
      │  └─ images/
      ├─ kie/
      ├─ previews/
      ├─ splits/
      └─ logs/
```

## 전체 생성 로직

데이터 생성 흐름은 아래 순서로 진행된다.

1. `configs/dataset_config.json`, `themes.json`, `fonts.json`, `company_domain_map.json`을 로드한다.
2. 시드를 `dataset_config.json`의 `seed` 값으로 고정한다.
3. 출력 디렉터리 하위 폴더를 한 번에 만든다.
4. 각 샘플마다 명함 규격, 방향, 테마, 폰트 프로파일, 필드 값을 무작위로 선택한다.
5. 레이아웃 함수가 실제 텍스트를 이미지에 렌더링하고, 렌더링된 위치를 기준으로 bbox와 reading order를 기록한다.
6. 샘플 단위 `master_annotations/*.json`과 YOLO 라벨, recognition crop, preview 이미지를 만든다.
7. 전체 생성이 끝나면 성공한 샘플 ID만 모아 `train/val/test` split을 만든다.
8. 누적된 마스터 어노테이션으로 COCO, PaddleOCR detection, PaddleOCR recognition, KIE JSON을 내보낸다.
9. 마지막에 실행 요약과 검증 요약을 작성하고 `docs/validation_report.md`를 갱신한다.

## 핵심 구성 요소

### 1. 설정 계층

- `dataset_config.json`
  - 클래스 목록, 샘플 수 기본값, 세로 명함 비율, preview 수, 필드 출현 확률, 카드 규격, 이름/회사/직책/주소 후보군을 담는다.
- `themes.json`
  - 배경색, 강조색, 본문색, 보조색 조합을 정의한다.
- `fonts.json`
  - Windows 폰트 후보와 `title/body/label` 스타일 조합을 정의한다.
- `company_domain_map.json`
  - 회사명과 이메일/웹사이트 도메인의 상관관계를 만든다.

### 2. 데이터 생성 계층

`DataFactory`가 실제 텍스트 값을 만든다.

- `name`, `company`, `position`은 기본적으로 항상 생성된다.
- `department`, `phone`, `mobile`, `fax`, `email`, `address`, `website`, `postcode`는 확률적으로 생성된다.
- 비어 있는 필드가 너무 많아지지 않도록 `min_present_fields`를 만족할 때까지 부족한 필드를 다시 채운다.
- 이메일은 회사 도메인 매핑을 우선 참고하고, 일부는 일반 도메인으로 섞어 현실성을 만든다.
- 전화번호는 지역번호/휴대폰 접두어와 구분자 `-`, `.`를 랜덤 조합한다.

### 3. 렌더링 계층

`generate_dataset_v2.py`는 텍스트를 먼저 결정한 뒤, 실제 그린 결과를 기준으로 어노테이션을 만든다.

- `make_background`
  - 단색 배경을 만들고 일부 샘플에는 약한 그라데이션을 넣는다.
- `RenderContext`
  - 텍스트 렌더링, 중앙 정렬, 연락처 prefix 부착, separator 선 그리기를 담당한다.
- `wrap_text`
  - 최대 폭을 넘는 경우 공백 기준으로 줄바꿈하고, 긴 단어는 문자 단위로 자른다.
- `AnnotationBuilder`
  - 필드별 `field_id`, `bbox_xyxy`, `reading_order`, `line_ids`, `line_boxes`를 누적한다.

### 4. 레이아웃 계층

가로/세로 명함은 별도 레이아웃 풀을 가진다.

- 가로 레이아웃
  - `horizontal_left`
  - `horizontal_right`
  - `split_horizontal`
  - `minimalist`
- 세로 레이아웃
  - `vertical_center`

즉, 가로 명함에서 세로 전용 레이아웃이 선택되지 않도록 분리되어 있다.

### 5. 산출물 파생 계층

한 장의 명함에서 아래 산출물이 동시에 만들어진다.

- 원본 이미지
  - `images/bizcard_<id>.jpg`
- YOLO detection 라벨
  - `labels_yolo/bizcard_<id>.txt`
- 마스터 어노테이션
  - `master_annotations/bizcard_<id>.json`
- recognition crop 이미지
  - `rec/images/<field_id>.jpg`
- KIE JSON
  - `kie/bizcard_<id>.json`
- preview 이미지
  - `previews/preview_<id>.jpg`

전체 샘플이 끝나면 추가로 아래 파일들이 생성된다.

- COCO
  - `coco/annotations_coco.json`
- YOLO용 메타
  - `classes.txt`
  - `data.yaml`
- PaddleOCR detection
  - `det/train.txt`, `det/val.txt`, `det/test.txt`
- PaddleOCR recognition
  - `rec/train.txt`, `rec/val.txt`, `rec/test.txt`
- split 파일
  - `splits/train.txt`, `splits/val.txt`, `splits/test.txt`
  - `splits/successful_ids.json`
- 실행/검증 로그
  - `logs/run_summary.json`
  - `logs/validation_summary.json`
  - `logs/failed_samples.jsonl` (실패가 있을 때 누적)

## 마스터 어노테이션 스키마

샘플 단위 JSON은 대략 아래 구조를 가진다.

```json
{
  "image_id": "00000",
  "image_path": "images/bizcard_00000.jpg",
  "image_size": { "width": 1063, "height": 591 },
  "card_orientation": "horizontal",
  "card_spec_mm": { "width": 90, "height": 50 },
  "layout_type": "horizontal_left",
  "theme_name": "classic_white",
  "font_profile": "modern_korean",
  "fields": [
    {
      "field_id": "00000_001",
      "field_class": "company",
      "field_class_id": 1,
      "rendered_text": "Tel. 02-1234-5678",
      "canonical_text": "02-1234-5678",
      "bbox_xyxy": [100, 120, 260, 150],
      "bbox_size": { "width": 160, "height": 30 },
      "reading_order": 1,
      "line_ids": ["00000_001_l01"],
      "line_boxes": [[100, 120, 260, 150]],
      "block_id": "00000_001_b01",
      "language": "ko"
    }
  ]
}
```

문서적으로 중요한 점은 다음 두 가지다.

- `rendered_text`
  - 카드에 실제로 그린 문자열이다. 연락처 prefix가 포함될 수 있다.
- `canonical_text`
  - prefix를 제외한 정규화된 값이다. KIE나 후처리 기준 텍스트로 쓰기 좋다.

## split 및 검증 로직

### split

- 성공한 샘플 ID 목록만 대상으로 셔플한다.
- 기본 비율은 `train 80% / val 10% / test 10%`다.
- split 텍스트 파일에는 `images/bizcard_<id>.jpg` 상대 경로가 기록된다.

### 검증

`src/qa_checks.py`가 아래 항목을 점검한다.

- 이미지 수와 YOLO 라벨 수가 일치하는지
- 이미지 수와 마스터 어노테이션 수가 일치하는지
- bbox가 음수이거나 이미지 범위를 벗어나지 않는지
- 비어 있는 `rendered_text`가 있는지
- 클래스, 레이아웃, 방향, 테마 분포가 어떻게 나왔는지

검증 결과는 JSON과 Markdown 둘 다 남는다.

## 실행 방법

기본 실행 예시는 아래와 같다.

```powershell
.\venv\Scripts\python.exe .\korean_bizcard_full_dataset_rebuild_v2\src\generate_dataset_v2.py --count 200 --output-name v2_base
```

자주 쓰는 인자는 다음과 같다.

- `--count`
  - 생성할 샘플 수. 생략하면 `dataset_config.json`의 `default_count`를 사용한다.
- `--output-name`
  - `output/<output-name>` 폴더명을 결정한다.
- `--preview-count`
  - preview 이미지 생성 수를 덮어쓴다.

예시:

```powershell
.\venv\Scripts\python.exe .\korean_bizcard_full_dataset_rebuild_v2\src\generate_dataset_v2.py --count 20 --output-name smoke_test --preview-count 5
```

## 현재 코드 기준 기본 분포

`dataset_config.json` 기준 주요 값은 다음과 같다.

- 기본 생성 수: `5000`
- 세로 명함 비율: `0.2`
- preview 수: `30`
- 최소 필드 수: `5`
- 명함 규격 분포:
  - `90 x 50mm`: `80%`
  - `85 x 55mm`: `20%`
- 클래스 수: `11`
  - `name`, `company`, `position`, `department`, `phone`, `mobile`, `fax`, `email`, `address`, `website`, `postcode`

## 운영 시 주의사항

- 폰트 로딩은 `fonts.json`의 Windows 경로를 기준으로 한다. 다른 OS에서 돌리려면 후보 경로를 먼저 수정해야 한다.
- 생성 단계에서는 blur, noise, rotate 같은 augmentation을 하지 않는다.
- preview는 앞에서부터 `preview_count`개 샘플에만 생성된다.
- 랜덤 시드는 고정되어 있지만, `count`, `output-name`, 설정 파일 내용이 바뀌면 결과도 달라진다.
- 실패 샘플은 전체 실행을 중단하지 않고 `failed_samples.jsonl`에 기록한 뒤 다음 샘플로 넘어간다.

## 관련 문서

- 전체 재설계 배경: [REBUILD_PLAN.md](C:/Users/m206/Desktop/byeol/korean_bizcard_full_dataset_rebuild_v2/REBUILD_PLAN.md)
- 생성 스크립트 상세 설명: [src/README_generate_dataset_v2.md](C:/Users/m206/Desktop/byeol/korean_bizcard_full_dataset_rebuild_v2/src/README_generate_dataset_v2.md)
