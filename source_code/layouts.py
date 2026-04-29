"""
레이아웃 정의 모듈
- 기본 텍스트 레이아웃(BASE_LAYOUTS) × 시각 요소 조합(VISUAL_FEATURES)
- 사용자 요청에 따라 배경패턴/도형/로고/QR 사용 여부를
  레이아웃 식별자에 명시적으로 포함시켜 데이터셋에서 구분 가능하도록 함
"""

# 기본 텍스트 배치 레이아웃 (어디에 어떤 정보가 들어가는가)
# 가로형(horizontal) / 세로형(vertical) 명함에 모두 적용 가능
BASE_LAYOUTS = [
    # 1. 좌측 정렬 - 가장 보편적인 레이아웃 (한국 표준 명함 90% 이상)
    {
        "id": "L01",
        "name": "left_aligned",
        "description": "좌측 정렬, 모든 정보 좌측에 정렬, 가로형 명함 가장 보편",
        "orientation": "horizontal",
    },
    # 2. 좌상단 회사 + 우측 인물정보 - 클래식 코퍼레이트
    {
        "id": "L02",
        "name": "topleft_company_right_info",
        "description": "좌상단에 회사명/로고 영역, 우측에 인물 정보",
        "orientation": "horizontal",
    },
    # 3. 상단 컬러 밴드 + 하단 정보 - 강조형
    {
        "id": "L03",
        "name": "horizontal_band_top",
        "description": "상단 30% 컬러밴드(회사정보) + 하단 70% 텍스트영역",
        "orientation": "horizontal",
    },
    # 4. 좌측 컬러 스트립 + 우측 정보
    {
        "id": "L04",
        "name": "vertical_strip_left",
        "description": "좌측 25% 컬러 스트립(회사정보) + 우측 75% 정보",
        "orientation": "horizontal",
    },
    # 5. 중앙 정렬 - 미니멀
    {
        "id": "L05",
        "name": "centered",
        "description": "모든 정보를 중앙 정렬",
        "orientation": "horizontal",
    },
    # 6. 좌우 분할(2-column) - 좌:이름/직함, 우:연락처
    {
        "id": "L06",
        "name": "two_column_split",
        "description": "좌측 절반: 이름/직함/회사, 우측 절반: 연락처",
        "orientation": "horizontal",
    },
    # 7. 세로형 - 상단 회사 + 중앙 이름 + 하단 연락처
    {
        "id": "L07",
        "name": "vertical_center",
        "description": "세로형 명함 - 상단 회사명, 중앙 큰 이름, 하단 연락처",
        "orientation": "vertical",
    },
    # 8. 세로형 - 모든 정보 좌측 정렬
    {
        "id": "L08",
        "name": "vertical_left_aligned",
        "description": "세로형 명함 - 모든 정보 좌측 정렬",
        "orientation": "vertical",
    },
]

# 시각 요소 사용여부 조합 - 16가지 모든 조합 중 의미있는 8개 선별
# (bg_pattern, shape, logo, qr) 4-bit
# bg_pattern: 배경 패턴(도트/라인/지오메트릭) 사용 여부
# shape: 도형(원/사각/삼각/구분선 외 장식 도형) 사용 여부
# logo: 추상 로고 사용 여부
# qr: QR 코드 사용 여부
VISUAL_FEATURE_PRESETS = [
    {"id": "V0", "name": "minimal_no_visual",     "bg_pattern": False, "shape": False, "logo": False, "qr": False, "description": "텍스트만 사용 - 배경/도형/로고/QR 모두 없음"},
    {"id": "V1", "name": "logo_only",             "bg_pattern": False, "shape": False, "logo": True,  "qr": False, "description": "로고만 사용 - 가장 흔한 미니멀 코퍼레이트"},
    {"id": "V2", "name": "logo_and_qr",           "bg_pattern": False, "shape": False, "logo": True,  "qr": True,  "description": "로고 + QR - 모던 비즈니스 명함"},
    {"id": "V3", "name": "logo_and_shape",        "bg_pattern": False, "shape": True,  "logo": True,  "qr": False, "description": "로고 + 도형 장식 (구분선/액센트도형)"},
    {"id": "V4", "name": "bg_pattern_and_logo",   "bg_pattern": True,  "shape": False, "logo": True,  "qr": False, "description": "배경 패턴 + 로고"},
    {"id": "V5", "name": "all_features",          "bg_pattern": True,  "shape": True,  "logo": True,  "qr": True,  "description": "모든 시각요소 사용 - 풀 디자인"},
    {"id": "V6", "name": "shape_only",            "bg_pattern": False, "shape": True,  "logo": False, "qr": False, "description": "도형만 (구분선/액센트) - 로고 없는 디자인"},
    {"id": "V7", "name": "qr_only",               "bg_pattern": False, "shape": False, "logo": False, "qr": True,  "description": "QR만 사용 - 디지털 중심 명함"},
]


def list_layouts():
    """기본 레이아웃 × 시각요소 조합으로 만들어진 모든 합성 레이아웃 반환.
    
    각 항목은 (composite_id, base_layout, visual_preset) 튜플.
    composite_id: "L01_V0" 형식 - 기본레이아웃 + 시각요소
    """
    out = []
    for base in BASE_LAYOUTS:
        for vis in VISUAL_FEATURE_PRESETS:
            cid = f"{base['id']}_{vis['id']}"
            out.append((cid, base, vis))
    return out


# 16개 레이아웃 선별 - 다양성 확보를 위해 base × visual 의 의미있는 조합만 사용
# 사용자 요청: "다양한 레이아웃을 만들고 ... 레이아웃별로 20장씩"
# → 8개 base × 8개 visual 모두는 64개로 너무 많으므로,
#   각 base 레이아웃이 다양한 visual 조합을 갖도록 16개 선별
SELECTED_LAYOUTS = [
    # base_id, visual_id  (총 16개 = 16 × 20장 = 320장)
    ("L01", "V0"),  # 좌측정렬 + 텍스트만 (가장 미니멀)
    ("L01", "V1"),  # 좌측정렬 + 로고
    ("L02", "V1"),  # 좌상단회사 + 로고
    ("L02", "V2"),  # 좌상단회사 + 로고 + QR
    ("L03", "V1"),  # 상단밴드 + 로고
    ("L03", "V5"),  # 상단밴드 + 모든시각요소
    ("L04", "V1"),  # 좌측스트립 + 로고
    ("L04", "V3"),  # 좌측스트립 + 로고 + 도형
    ("L05", "V1"),  # 중앙정렬 + 로고
    ("L05", "V6"),  # 중앙정렬 + 도형만
    ("L06", "V2"),  # 두칸분할 + 로고 + QR
    ("L06", "V4"),  # 두칸분할 + 배경패턴 + 로고
    ("L07", "V1"),  # 세로형중앙 + 로고
    ("L07", "V2"),  # 세로형중앙 + 로고 + QR
    ("L08", "V1"),  # 세로형좌측 + 로고
    ("L08", "V7"),  # 세로형좌측 + QR만
]


def get_base_layout(base_id):
    for b in BASE_LAYOUTS:
        if b["id"] == base_id:
            return b
    raise KeyError(base_id)


def get_visual_preset(vis_id):
    for v in VISUAL_FEATURE_PRESETS:
        if v["id"] == vis_id:
            return v
    raise KeyError(vis_id)


def get_composite_layout(base_id, vis_id):
    """레이아웃 + 시각요소 결합 정보 반환"""
    base = get_base_layout(base_id)
    vis = get_visual_preset(vis_id)
    return {
        "composite_id": f"{base_id}_{vis_id}",
        "base_layout_id": base_id,
        "base_layout_name": base["name"],
        "visual_preset_id": vis_id,
        "visual_preset_name": vis["name"],
        "orientation": base["orientation"],
        "uses_bg_pattern": vis["bg_pattern"],
        "uses_shape": vis["shape"],
        "uses_logo": vis["logo"],
        "uses_qr": vis["qr"],
        "description": f"{base['description']} | {vis['description']}",
    }
