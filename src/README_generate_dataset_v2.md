# generate_dataset_v2.py README

이 문서는 `src/generate_dataset_v2.py`가 실제 데이터를 어떻게 만드는지 코드 기준으로 설명한다. 대상은 생성기 수정자, 실험 파이프라인 작성자, 어노테이션 포맷을 재사용하려는 사람이다.

## 파일 책임

`generate_dataset_v2.py`는 아래 역할을 한 파일에서 수행한다.

- 설정 로드
- synthetic 명함 텍스트 생성
- 카드 배경/텍스트 렌더링
- bbox 및 마스터 어노테이션 구성
- YOLO, COCO, PaddleOCR 스타일 산출물 생성
- split 생성
- 실행 로그와 검증 결과 저장

검증 자체의 세부 구현은 `qa_checks.py`에 있다.

## 실행 진입점

실행 흐름은 `main()`에서 시작한다.

1. `parse_args()`가 CLI 인자를 읽는다.
2. `dataset_config.json`을 다시 읽어 기본 샘플 수를 확인한다.
3. `run_generation(count, output_name, preview_count)`를 호출한다.
4. 완료 후 생성된 출력 디렉터리 경로를 출력한다.

## 상위 호출 흐름

코드의 큰 흐름은 아래와 같다.

```text
main
└─ parse_args
└─ run_generation
   ├─ load_json(configs/*)
   ├─ build_output_dirs
   ├─ FontManager(...)
   ├─ DataFactory(...)
   ├─ for each image_id
   │  ├─ generate_card
   │  │  ├─ choose_card_spec
   │  │  ├─ make_background
   │  │  ├─ DataFactory.build
   │  │  ├─ layout function 실행
   │  │  └─ build_record
   │  ├─ 이미지 저장
   │  ├─ master json 저장
   │  ├─ YOLO 저장
   │  ├─ preview 저장
   │  └─ recognition crop 저장
   ├─ create_splits
   ├─ export_all
   ├─ validate_output
   └─ write_validation_report
```

## 주요 클래스

### `FontManager`

폰트 후보와 폰트 프로파일을 관리한다.

- 입력: `fonts.json`
- 내부 역할:
  - `regular`, `bold` 후보 경로 중 실제 존재하는 파일만 남긴다.
  - `bold`가 없으면 `regular`를 fallback으로 사용한다.
  - `(style, size, profile_name)` 단위로 폰트를 캐시한다.
- 주요 메서드:
  - `choose_profile()`: 프로파일 하나를 랜덤 선택
  - `get(style, size, profile_name)`: 실제 `ImageFont.truetype` 반환

### `DataFactory`

필드 값을 생성하는 핵심 팩토리다.

- `gen_name()`: 성 + 이름 조합
- `gen_company()`: 회사명 후보 선택
- `gen_position()`: 가중치 기반 직책 선택
- `gen_department()`: 부서 선택
- `gen_phone(mobile)`: 일반전화/휴대폰 번호 생성
- `gen_address()`: 시/구/도로/상세번호 조합
- `gen_email(name, company)`: 이름 기반 stem + 회사 도메인 또는 일반 도메인
- `gen_website(company)`: `www.` 유무를 섞은 도메인 생성
- `gen_postcode()`: 5자리 우편번호 생성
- `build()`: 전체 필드 딕셔너리 생성

`build()`의 중요한 제약은 다음과 같다.

- `name`, `company`, `position`은 항상 존재한다.
- 나머지 필드는 `field_probabilities`에 따라 조건부 생성된다.
- 최종적으로 채워진 필드 개수가 `min_present_fields`보다 적으면 비어 있는 필드를 순서대로 다시 채운다.

### `AnnotationBuilder`

필드 단위 어노테이션을 누적한다.

- `image_id`와 `classes` 매핑을 받아 시작한다.
- `add()` 호출마다 다음 값을 기록한다.
  - `field_id`
  - `field_class`
  - `field_class_id`
  - `rendered_text`
  - `canonical_text`
  - `bbox_xyxy`
  - `bbox_size`
  - `reading_order`
  - `line_ids`
  - `line_boxes`
  - `block_id`
  - `language`

`reading_order`는 `add()` 호출 순서대로 1씩 증가한다.

### `RenderContext`

렌더링과 bbox 기록을 함께 다루는 컨텍스트 객체다.

- 포함 객체:
  - `image`, `draw`
  - `builder`
  - `font_manager`
  - `font_profile`
  - `theme`
  - `prefixes`
- 주요 메서드:
  - `font(role, size)`: title/body/label용 폰트 선택
  - `render_text_block(...)`: 좌측 기준 텍스트 렌더링 및 bbox 기록
  - `render_centered(...)`: 중앙 정렬 렌더링 및 bbox 기록
  - `render_contact(...)`: 연락처 prefix를 붙여 렌더링
  - `separator(...)`: 구분선 그리기

중요한 점은 bbox가 텍스트를 그리기 전의 추정값이 아니라, `ImageDraw.textbbox()` 결과를 기반으로 만들어진다는 것이다.

## 주요 유틸리티 함수

### 텍스트/박스 처리

- `text_width(font, text)`
  - 글자 폭 계산
- `wrap_text(text, font, max_width)`
  - 폭을 넘는 텍스트를 줄바꿈
- `union_boxes(boxes)`
  - 여러 줄 bbox를 하나의 field bbox로 합침
- `clamp_box(box, width, height)`
  - 이미지 바깥으로 벗어난 좌표를 안전하게 자름

### 설정/파일 처리

- `load_json(path)`
  - UTF-8 JSON 읽기
- `write_json(path, payload)`
  - 디렉터리 생성 후 JSON 저장
- `build_output_dirs(root)`
  - 출력 폴더 트리 생성

## 레이아웃 함수

레이아웃 함수는 `RenderContext`와 `data`를 받아 실제 배치를 결정한다.

### 가로 명함용

- `layout_horizontal_left`
  - 좌측 정렬 중심, 회사명 → 이름 → 부서/직책 → 연락처 순
- `layout_horizontal_right`
  - 좌측 연락처, 중앙 구분선, 우측 프로필 정보 구조
- `layout_split_horizontal`
  - 상단 컬러 배너 + 하단 연락처 영역 구조
- `layout_minimalist`
  - 큰 이름, 얇은 separator, 단순한 정보 배치 구조

### 세로 명함용

- `layout_vertical_center`
  - 중앙 정렬 중심의 세로형 카드

레이아웃 선택 규칙은 `generate_card()`에 있다.

- `vertical_ratio`에 따라 세로/가로를 먼저 정한다.
- 세로면 `VERTICAL_LAYOUTS`, 가로면 `HORIZONTAL_LAYOUTS`에서만 선택한다.

## 샘플 1장 생성 로직

`generate_card()`가 명함 한 장을 만든다.

1. `choose_card_spec()`로 카드 규격 선택
2. `vertical_ratio`에 따라 방향 선택
3. 방향에 맞춰 `(width, height)` 결정
4. 테마 선택 후 `make_background()`로 배경 생성
5. 폰트 프로파일 선택
6. `AnnotationBuilder`와 `RenderContext` 생성
7. `DataFactory.build()`로 필드 값 생성
8. 방향에 맞는 레이아웃 함수를 실행
9. `build_record()`로 샘플 단위 마스터 JSON 생성
10. 결과로 `(PIL.Image, record)` 반환

## 전체 배치 생성 로직

`run_generation()`이 실행 전체를 관리한다.

### 준비 단계

- 설정 파일 로드
- 필요하면 `preview_count`를 CLI 값으로 덮어쓰기
- `random.seed(config["seed"])` 설정
- 출력 디렉터리 생성
- `FontManager`, `DataFactory` 초기화

### 반복 생성 단계

각 `image_id`마다 아래를 수행한다.

1. `generate_card()` 호출
2. `images/bizcard_<id>.jpg` 저장
3. `master_annotations/bizcard_<id>.json` 저장
4. `labels_yolo/bizcard_<id>.txt` 저장
5. preview 대상이면 `previews/preview_<id>.jpg` 저장
6. 각 필드 bbox를 crop해서 `rec/images/<field_id>.jpg` 저장
7. recognition용 `(crop_path, text)` 엔트리를 메모리에 누적

예외가 발생하면 실행을 멈추지 않고 아래 정보를 `logs/failed_samples.jsonl`에 남긴다.

- `image_id`
- `timestamp`
- `error`
- `traceback`

### 후처리 단계

반복 생성이 끝나면 다음을 수행한다.

1. `create_splits()`로 성공 샘플 ID만 분할
2. `export_all()`로 전체 포맷 산출물 생성
3. `logs/run_summary.json` 저장
4. `validate_output()` 실행
5. `logs/validation_summary.json` 저장
6. `write_validation_report()`로 `docs/validation_report.md` 갱신

## `export_all()`이 만드는 것

`export_all()`은 메모리에 모아둔 `records`, `rec_entries`, `splits`를 이용해 파생 포맷을 정리한다.

### split 파일

- `splits/train.txt`
- `splits/val.txt`
- `splits/test.txt`
- `splits/successful_ids.json`

각 split 파일은 `images/bizcard_<id>.jpg` 상대 경로를 담는다.

### COCO

`coco/annotations_coco.json` 구조:

- `images`
- `annotations`
- `categories`

annotation마다 아래 항목이 들어간다.

- `bbox`
- `area`
- `category_id`
- `text`
- `canonical_text`

### PaddleOCR detection

`det/<split>.txt` 한 줄 형식:

```text
images/bizcard_00000.jpg    [{"transcription":"...", "points":[[x1,y1],[x2,y1],[x2,y2],[x1,y2]]}, ...]
```

### PaddleOCR recognition

`rec/<split>.txt` 한 줄 형식:

```text
rec/images/00000_001.jpg    렌더링된 텍스트
```

여기에는 `canonical_text`가 아니라 실제 카드에 보이는 `rendered_text`가 들어간다.

### KIE JSON

`kie/bizcard_<id>.json`은 현재 마스터 어노테이션과 동일한 구조를 저장한다.

## YOLO 변환 규칙

`save_yolo()`는 각 필드 bbox를 YOLO 형식으로 변환한다.

- 입력 bbox: `xyxy`
- 출력: `class_id cx cy bw bh`
- 좌표는 이미지 크기로 정규화된다.
- 폭 또는 높이가 0 이하인 박스는 건너뛴다.

정규화 계산은 `bbox_to_yolo()`가 담당한다.

## CLI 인자

`parse_args()`에서 지원하는 인자는 세 개다.

- `--count`
  - 생성 개수
- `--output-name`
  - 출력 폴더명, 기본값 `v2_base`
- `--preview-count`
  - 미리보기 생성 개수 override

예시:

```powershell
.\venv\Scripts\python.exe .\korean_bizcard_full_dataset_rebuild_v2\src\generate_dataset_v2.py --count 100 --output-name exp_001 --preview-count 10
```

## 코드 수정 시 먼저 봐야 할 지점

요구사항에 따라 수정 포인트는 보통 아래처럼 나뉜다.

- 필드 값 분포를 바꾸고 싶을 때
  - `configs/dataset_config.json`
  - `DataFactory`
- 회사별 이메일/웹사이트 도메인을 바꾸고 싶을 때
  - `configs/company_domain_map.json`
- 카드 색상 계열을 바꾸고 싶을 때
  - `configs/themes.json`
- 폰트 구성을 바꾸고 싶을 때
  - `configs/fonts.json`
- 배치 스타일을 추가하고 싶을 때
  - 새 layout 함수 작성
  - `HORIZONTAL_LAYOUTS` 또는 `VERTICAL_LAYOUTS` 등록
- 어노테이션 스키마를 바꾸고 싶을 때
  - `AnnotationBuilder.add()`
  - `build_record()`
  - `export_all()`
- 검증 규칙을 늘리고 싶을 때
  - `qa_checks.py`

## 구현상 주의사항

- 생성 시드는 고정되지만, 코드/설정 변경 후에는 같은 `seed`여도 결과 분포가 달라질 수 있다.
- recognition crop은 field bbox 그대로 잘라 저장하므로, layout 변경 시 bbox 품질이 전체 파이프라인 품질에 직접 영향을 준다.
- 현재 preview는 일부 샘플만 만든다. 전체를 시각 검수하고 싶다면 `--preview-count`를 늘려야 한다.
- `FontManager`는 후보 리스트의 첫 번째 존재 폰트를 사용한다. 후보 우선순위가 곧 실제 출력 스타일 우선순위다.
- `write_validation_report()`는 `docs/validation_report.md`를 덮어쓴다. 실험별 보고서를 따로 남기려면 경로 전략을 바꿔야 한다.

## 함께 읽을 파일

- 메인 개요: [../README.md](C:/Users/m206/Desktop/byeol/korean_bizcard_full_dataset_rebuild_v2/README.md)
- 검증 로직: [qa_checks.py](C:/Users/m206/Desktop/byeol/korean_bizcard_full_dataset_rebuild_v2/src/qa_checks.py)
