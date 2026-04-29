# 한국어 명함 합성 데이터셋

`business_card_synthesis_guide.md`와 `business_card_design_schema.json`을 기반으로 생성된 합성 명함 데이터셋입니다.

## 통계

- **총 이미지 수:** 320장
- **레이아웃 종류:** 16개 (8개 텍스트 배치 × 다양한 시각요소 조합)
- **레이아웃당 샘플:** 20장
- **해상도:** 300 DPI
- **이미지 사이즈:** 가로형 1063×591px (90×50mm) / 세로형 591×1063px (50×90mm)
- **렌더링 방식:** Pillow (증강 없음, 원본만)

## 디렉토리 구조

```
bizcard_dataset/
├── images/                        # 320장의 .jpg 이미지
│   ├── bizcard_00000.jpg
│   ├── bizcard_00001.jpg
│   └── ...
├── annotations/                   # 320개의 .json 어노테이션
│   ├── bizcard_00000.json
│   ├── bizcard_00001.json
│   └── ...
├── dataset_summary.json           # 레이아웃별 image_id 매핑 + 시각요소 메타
├── preview_16_layouts.jpg         # 16개 레이아웃 미리보기 콜라주
├── source_code/                   # 합성기 소스코드 (Python)
│   ├── catalog.py                 # 색상/폰트/회사명 카탈로그
│   ├── layouts.py                 # 레이아웃 정의 (BASE × VISUAL 조합)
│   ├── visual_elements.py         # 배경패턴/도형/로고/QR 렌더러
│   ├── text_render.py             # 텍스트+bbox 유틸
│   ├── card_renderer.py           # 8개 base 레이아웃 렌더 함수
│   └── synthesize.py              # 메인 진입점
└── README.md
```

## 레이아웃 식별 체계

각 명함의 `layout_composite_id`는 `LXX_VY` 형식으로, 두 부분으로 구성됩니다.

### 8개 텍스트 배치 (BASE)

| ID  | 이름 | 방향 | 설명 |
|-----|------|------|------|
| L01 | left_aligned | 가로 | 좌측 정렬 (한국 표준 명함) |
| L02 | topleft_company_right_info | 가로 | 좌상단 회사 + 우측 인물정보 |
| L03 | horizontal_band_top | 가로 | 상단 컬러 밴드 + 하단 텍스트 |
| L04 | vertical_strip_left | 가로 | 좌측 컬러 스트립 + 우측 정보 |
| L05 | centered | 가로 | 모든 정보 중앙 정렬 |
| L06 | two_column_split | 가로 | 좌우 두칸 분할 |
| L07 | vertical_center | 세로 | 상단 회사 + 중앙 이름 + 하단 연락처 |
| L08 | vertical_left_aligned | 세로 | 모든 정보 좌측 정렬 |

### 시각 요소 조합 (VISUAL) — 사용 여부가 ID에 명시됨

각 합성 명함은 4개 시각요소(배경패턴/도형/로고/QR)의 사용 여부를 `layout_features` 필드에 부울로 명시합니다.

| ID  | 이름 | bg_pattern | shape | logo | qr |
|-----|------|------|------|------|------|
| V0 | minimal_no_visual | ❌ | ❌ | ❌ | ❌ |
| V1 | logo_only | ❌ | ❌ | ✅ | ❌ |
| V2 | logo_and_qr | ❌ | ❌ | ✅ | ✅ |
| V3 | logo_and_shape | ❌ | ✅ | ✅ | ❌ |
| V4 | bg_pattern_and_logo | ✅ | ❌ | ✅ | ❌ |
| V5 | all_features | ✅ | ✅ | ✅ | ✅ |
| V6 | shape_only | ❌ | ✅ | ❌ | ❌ |
| V7 | qr_only | ❌ | ❌ | ❌ | ✅ |

### 선별된 16개 레이아웃 (각 20장씩)

```
L01_V0  텍스트만 (가장 미니멀)            L05_V1  중앙정렬 + 로고
L01_V1  좌측정렬 + 로고                   L05_V6  중앙정렬 + 도형만
L02_V1  좌상단회사 + 로고                 L06_V2  두칸분할 + 로고+QR
L02_V2  좌상단회사 + 로고+QR              L06_V4  두칸분할 + 패턴+로고
L03_V1  상단밴드 + 로고                   L07_V1  세로중앙 + 로고
L03_V5  상단밴드 + 모든시각요소           L07_V2  세로중앙 + 로고+QR
L04_V1  좌측스트립 + 로고                 L08_V1  세로좌측 + 로고
L04_V3  좌측스트립 + 로고+도형            L08_V7  세로좌측 + QR만
```

## 어노테이션 형식

첨부된 `bizcard_00000.json` 형식과 100% 호환됩니다. 추가로 시각요소 식별을 위한 키들이 포함됩니다.

```json
{
  "image_id": "00000",
  "image_path": "images/bizcard_00000.jpg",
  "image_size": {"width": 1063, "height": 591},
  "card_orientation": "horizontal",
  "card_spec_mm": {"width": 90, "height": 50},
  "dpi": 300,
  "layout_type": "left_aligned",
  "layout_composite_id": "L01_V0",
  "layout_features": {
    "uses_bg_pattern": false,
    "uses_shape": false,
    "uses_logo": false,
    "uses_qr": false
  },
  "visual_elements": {
    "background_pattern": null,
    "decorative_shapes": [],
    "logo": null,
    "qr_code": null
  },
  "theme_name": "coral",
  "theme_colors": {"bg": "#FF6B6B", "text": "#FFFFFF", "muted": "#FFD8D8", "accent": "#2C3E50"},
  "font_profile": "modern_korean",
  "font_sizes_pt": {"name_pt": 12, "title_pt": 8.5, "body_pt": 7.5, "accent_pt": 7.0},
  "fields": [
    {
      "field_id": "00000_001",
      "field_class": "company",
      "field_class_id": 1,
      "rendered_text": "메이플스튜디오",
      "canonical_text": "메이플스튜디오",
      "bbox_xyxy": [59, 83, 329, 123],
      "bbox_size": {"width": 270, "height": 40},
      "reading_order": 1,
      "line_ids": ["00000_001_l01"],
      "line_boxes": [[59, 83, 329, 123]],
      "block_id": "00000_001_b01",
      "language": "ko"
    }
    // ... 다른 필드들
  ]
}
```

### 필드 클래스 ID

```
0: name        (이름)        - 필수
1: company     (회사명)      - 필수
2: position    (직급)        - 선택 (85%)
3: department  (부서)        - 선택 (70%)
4: phone       (전화)        - 선택 (75%)
5: mobile      (휴대전화)    - 필수
6: fax         (팩스)        - 선택 (20%)
7: email       (이메일)      - 필수
8: address     (주소)        - 선택 (85%)
9: website     (웹사이트)    - 선택 (80%)
10: tagline    (태그라인)    - 사용 안 함
```

### 필수 vs 선택 필드

- **필수 (모든 명함에 포함):** name, company, mobile, email
- **선택 (확률적 포함):** position, department, phone, fax, address, website

## 다양성

- **색상 테마:** 30종 (pure_white, navy_classic, charcoal, sage, coral, green_accent 등)
- **폰트 프로파일:** 7종 (modern_korean, classic_korean_serif, mixed_serif_name_sans_body 등)
- **사용 폰트:** Noto Sans CJK KR / Noto Serif CJK KR (오픈 라이선스)

## 저작권/상표권 안전성

- **회사명:** 26개 가상 회사 풀 (라인플러스, 누리테크, 메이플스튜디오 등 - 모두 합성)
- **도메인:** `*.example.com` / `*.example.co.kr` (IANA 예약 안전 도메인)
- **모바일 번호:** `010-0XXX-XXXX` 안전대역
- **이메일:** `userNNNN@*.example.com` (개인 식별 불가)
- **로고:** 절차적 추상 로고 (5종 - circle/rounded_square/geometric/ring/triangle)
- **폰트:** Noto Sans/Serif CJK (Open Font License)

## 사용 방법 (재생성)

```bash
cd source_code
python synthesize.py --output ./bizcard_dataset --samples 20 --seed 42
```

`--samples` 값을 변경하면 레이아웃당 생성 장수를 조절할 수 있습니다.
`--seed` 값을 바꾸면 다른 콘텐츠 조합으로 재생성됩니다.

## 다운스트림 활용 예시

이 데이터셋은 다음 작업에 활용 가능합니다.
- 명함 OCR / 텍스트 검출 모델 학습
- 명함 정보 추출(NER) 모델 학습 (필드 분류 11종)
- 레이아웃 분석 모델 (16종 레이아웃 분류)
- 시각 요소 검출 모델 (배경패턴/도형/로고/QR 유무 분류)
