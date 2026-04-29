"""
명함 합성기 카탈로그
- 색상 팔레트, 폰트 매핑, 가짜 회사/이름 데이터를 모아둔 모듈
- 모든 데이터는 가공된 가상 데이터이며 저작권/상표권 회피를 우선시함
"""
import os
import random

# =============================================================================
# 폰트 경로 (시스템에 설치된 오픈 라이선스 폰트만 사용)
# =============================================================================
NOTO_SANS_KR = {
    "Thin":      "/usr/share/fonts/opentype/noto/NotoSansCJK-Thin.ttc",
    "Light":     "/usr/share/fonts/opentype/noto/NotoSansCJK-Light.ttc",
    "DemiLight": "/usr/share/fonts/opentype/noto/NotoSansCJK-DemiLight.ttc",
    "Regular":   "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "Medium":    "/usr/share/fonts/opentype/noto/NotoSansCJK-Medium.ttc",
    "Bold":      "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "Black":     "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc",
}
NOTO_SERIF_KR = {
    "ExtraLight": "/usr/share/fonts/opentype/noto/NotoSerifCJK-ExtraLight.ttc",
    "Light":      "/usr/share/fonts/opentype/noto/NotoSerifCJK-Light.ttc",
    "Regular":    "/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc",
    "Medium":     "/usr/share/fonts/opentype/noto/NotoSerifCJK-Medium.ttc",
    "SemiBold":   "/usr/share/fonts/opentype/noto/NotoSerifCJK-SemiBold.ttc",
    "Bold":       "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc",
    "Black":      "/usr/share/fonts/opentype/noto/NotoSerifCJK-Black.ttc",
}

# Noto Sans CJK는 .ttc 콜렉션 안에 KR/JP/SC/TC 4개의 face가 들어있음
# 보통 KR은 index=1 (Source Han Sans/Noto Sans CJK 표준)
# 하지만 Pillow는 .ttc에서 index=0이 기본이며 KR 글리프도 잘 렌더됨
NOTO_KR_INDEX = 1  # KR face

# =============================================================================
# Font Profile: 명함 한 장에 적용되는 폰트 설정 묶음
# =============================================================================
FONT_PROFILES = {
    "modern_korean": {
        "name_font":    NOTO_SANS_KR["Bold"],
        "title_font":   NOTO_SANS_KR["Medium"],
        "body_font":    NOTO_SANS_KR["Regular"],
        "accent_font":  NOTO_SANS_KR["Light"],
    },
    "modern_korean_strong": {
        "name_font":    NOTO_SANS_KR["Black"],
        "title_font":   NOTO_SANS_KR["Bold"],
        "body_font":    NOTO_SANS_KR["Regular"],
        "accent_font":  NOTO_SANS_KR["Medium"],
    },
    "modern_korean_light": {
        "name_font":    NOTO_SANS_KR["Medium"],
        "title_font":   NOTO_SANS_KR["Regular"],
        "body_font":    NOTO_SANS_KR["DemiLight"],
        "accent_font":  NOTO_SANS_KR["Light"],
    },
    "classic_korean_serif": {
        "name_font":    NOTO_SERIF_KR["Bold"],
        "title_font":   NOTO_SERIF_KR["Medium"],
        "body_font":    NOTO_SERIF_KR["Regular"],
        "accent_font":  NOTO_SERIF_KR["Light"],
    },
    "classic_korean_serif_strong": {
        "name_font":    NOTO_SERIF_KR["Black"],
        "title_font":   NOTO_SERIF_KR["SemiBold"],
        "body_font":    NOTO_SERIF_KR["Regular"],
        "accent_font":  NOTO_SERIF_KR["Light"],
    },
    "mixed_serif_name_sans_body": {
        "name_font":    NOTO_SERIF_KR["Bold"],
        "title_font":   NOTO_SANS_KR["Medium"],
        "body_font":    NOTO_SANS_KR["Regular"],
        "accent_font":  NOTO_SANS_KR["Light"],
    },
    "mixed_sans_name_serif_body": {
        "name_font":    NOTO_SANS_KR["Black"],
        "title_font":   NOTO_SERIF_KR["Medium"],
        "body_font":    NOTO_SERIF_KR["Regular"],
        "accent_font":  NOTO_SERIF_KR["Light"],
    },
}

# =============================================================================
# 색상 테마: (배경, 텍스트, 보조텍스트, 강조)
# =============================================================================
COLOR_THEMES = {
    # ----- minimal_corporate -----
    "pure_white":     {"bg": "#FFFFFF", "text": "#1A1A1A", "muted": "#666666", "accent": "#3B82F6"},
    "off_white":      {"bg": "#F8F7F4", "text": "#222222", "muted": "#5A5A5A", "accent": "#8B6F47"},
    "cream_paper":    {"bg": "#FAF6F0", "text": "#2C2C2C", "muted": "#6B6B6B", "accent": "#A8763E"},
    "light_gray":     {"bg": "#EFEFEF", "text": "#1F2937", "muted": "#5B6470", "accent": "#DC2626"},
    "soft_beige":     {"bg": "#F0EAE0", "text": "#2C2C2C", "muted": "#6B5E50", "accent": "#1E3A5F"},
    # ----- dark_luxury -----
    "pure_black":     {"bg": "#0A0A0A", "text": "#FFFFFF", "muted": "#B0B0B0", "accent": "#D4AF37"},
    "charcoal":       {"bg": "#1F1F1F", "text": "#F5F5F5", "muted": "#A8A8A8", "accent": "#C9A961"},
    "midnight":       {"bg": "#0F1419", "text": "#E8E8E8", "muted": "#9CA3AF", "accent": "#A78BFA"},
    "deep_forest":    {"bg": "#0F2818", "text": "#E8E1D5", "muted": "#A8B0A4", "accent": "#C9A961"},
    "wine":           {"bg": "#3D1F2A", "text": "#F0E5D8", "muted": "#B5A39A", "accent": "#D4AF37"},
    # ----- trust_finance -----
    "navy_classic":   {"bg": "#0F2A4F", "text": "#FFFFFF", "muted": "#B8C7D9", "accent": "#C9A961"},
    "navy_on_white":  {"bg": "#FFFFFF", "text": "#0F2A4F", "muted": "#5B6E85", "accent": "#0F2A4F"},
    "steel_blue":     {"bg": "#34495E", "text": "#ECF0F1", "muted": "#B0BEC5", "accent": "#3498DB"},
    "royal_blue":     {"bg": "#1E3A8A", "text": "#F8FAFC", "muted": "#BFCAE0", "accent": "#FBBF24"},
    "tech_blue":      {"bg": "#FFFFFF", "text": "#1E40AF", "muted": "#647596", "accent": "#06B6D4"},
    # ----- natural / wellness -----
    "sage":           {"bg": "#A8B5A0", "text": "#2C3E2D", "muted": "#5A6A57", "accent": "#FFFFFF"},
    "forest":         {"bg": "#2D5016", "text": "#F5F1E8", "muted": "#B5BAA5", "accent": "#C9A961"},
    "mint":           {"bg": "#E8F5E9", "text": "#1B5E20", "muted": "#5A7A60", "accent": "#388E3C"},
    "olive":          {"bg": "#6B7C32", "text": "#FAF7F0", "muted": "#D0D2BC", "accent": "#FAF7F0"},
    "eucalyptus":     {"bg": "#88A095", "text": "#1F2D26", "muted": "#4A5852", "accent": "#1F2D26"},
    # ----- creative_trendy -----
    "coral":          {"bg": "#FF6B6B", "text": "#FFFFFF", "muted": "#FFD8D8", "accent": "#2C3E50"},
    "mustard_black":  {"bg": "#F4C842", "text": "#0A0A0A", "muted": "#444444", "accent": "#0A0A0A"},
    "terracotta":     {"bg": "#C66B3D", "text": "#FAF6F0", "muted": "#E8D5C4", "accent": "#2C1810"},
    "lavender":       {"bg": "#B8A5D9", "text": "#2C2545", "muted": "#5D5478", "accent": "#F5F0FA"},
    # ----- pastel_soft -----
    "blush":          {"bg": "#F8E1E4", "text": "#5D4954", "muted": "#9A8590", "accent": "#A8767E"},
    "powder_blue":    {"bg": "#D8E5EA", "text": "#3A4A55", "muted": "#7A8A95", "accent": "#5D7A8A"},
    "butter":         {"bg": "#FFF8E1", "text": "#5D4E2C", "muted": "#9A8E70", "accent": "#C9A961"},
    "lilac":          {"bg": "#E8DFF2", "text": "#3D3052", "muted": "#7A6E8E", "accent": "#7B5FA8"},
    # ----- green accent (요청된 어노테이션 예시와 매칭) -----
    "green_accent":   {"bg": "#FFFFFF", "text": "#1B1B1B", "muted": "#5A5A5A", "accent": "#06C755"},
    "green_dark":     {"bg": "#0A2818", "text": "#FFFFFF", "muted": "#B5C6BC", "accent": "#06C755"},
}

# =============================================================================
# 가상 회사명 풀 (실존 브랜드 회피 - 합성된 이름들)
# =============================================================================
COMPANY_POOL = [
    # 한글 + 영문 표기 분리. domain은 example.* 사용
    {"ko": "라인플러스",     "en": "LinePlus",        "domain": "lineplus.example.com"},
    {"ko": "누리테크",       "en": "Nuri Tech",       "domain": "nuritech.example.co.kr"},
    {"ko": "한솔디지털",     "en": "Hansol Digital",  "domain": "hansol.example.co.kr"},
    {"ko": "푸른소프트",     "en": "Pureun Soft",     "domain": "pureun.example.org"},
    {"ko": "코어시스템즈",   "en": "Core Systems",    "domain": "coresys.example.com"},
    {"ko": "네오랩스",       "en": "Neo Labs",        "domain": "neolabs.example.com"},
    {"ko": "아이리스",       "en": "Iris",            "domain": "iris.example.co.kr"},
    {"ko": "메이플스튜디오", "en": "Maple Studio",    "domain": "maple.example.com"},
    {"ko": "오로라파트너스", "en": "Aurora Partners", "domain": "aurora.example.com"},
    {"ko": "써밋컨설팅",     "en": "Summit Consulting","domain": "summit.example.org"},
    {"ko": "퀀텀웍스",       "en": "Quantum Works",   "domain": "qworks.example.com"},
    {"ko": "노바미디어",     "en": "Nova Media",      "domain": "novamedia.example.com"},
    {"ko": "솔라이트",       "en": "SolaLite",        "domain": "solalite.example.com"},
    {"ko": "엘로힘",         "en": "Elohim",          "domain": "elohim.example.org"},
    {"ko": "비전플러스",     "en": "Vision Plus",     "domain": "visionplus.example.com"},
    {"ko": "베리타스",       "en": "Veritas",         "domain": "veritas.example.org"},
    {"ko": "드림웍스코리아", "en": "DreamWorx Korea", "domain": "dwk.example.co.kr"},
    {"ko": "문라이트",       "en": "Moonlight",       "domain": "moonlight.example.com"},
    {"ko": "프리즘",         "en": "Prism",           "domain": "prism.example.org"},
    {"ko": "하이퍼노드",     "en": "HyperNode",       "domain": "hypernode.example.com"},
    {"ko": "그린피크",       "en": "GreenPeak",       "domain": "greenpeak.example.com"},
    {"ko": "스타라이트",     "en": "Starlight",       "domain": "starlight.example.org"},
    {"ko": "이클립스랩",     "en": "Eclipse Lab",     "domain": "eclipse.example.com"},
    {"ko": "리바이브",       "en": "Revive",          "domain": "revive.example.co.kr"},
    {"ko": "오션블루",       "en": "OceanBlue",       "domain": "oceanblue.example.com"},
    {"ko": "케이엠스튜디오", "en": "KM Studio",       "domain": "kmstudio.example.com"},
]

# =============================================================================
# 한국식 성씨 + 이름 (가상 인물 생성용)
# =============================================================================
KOREAN_SURNAMES = [
    "김", "이", "박", "최", "정", "강", "조", "윤", "장", "임",
    "한", "오", "서", "신", "권", "황", "안", "송", "유", "홍",
    "전", "고", "문", "양", "손", "배", "백", "허", "노", "심"
]
KOREAN_GIVEN_NAMES = [
    "도윤", "서준", "하준", "지호", "주원", "지후", "준서", "준우", "도현", "건우",
    "현우", "지훈", "우진", "선우", "유준", "은우", "민준", "예준", "시우", "연우",
    "서연", "서윤", "지우", "서현", "민서", "지유", "수아", "지아", "유나", "하은",
    "윤서", "지민", "채원", "지윤", "은서", "수빈", "다은", "예은", "소율", "예린",
    "민지", "수민", "현지", "예원", "보영", "혜진", "다현", "유진", "진영", "혜원",
]

# 부서 풀
DEPARTMENTS = [
    "개발팀", "기획팀", "마케팅팀", "영업팀", "디자인팀", "재무팀",
    "인사팀", "전략기획팀", "프로덕트팀", "고객지원팀", "운영팀", "QA팀",
    "데이터분석팀", "법무팀", "총무팀", "사업개발팀", "콘텐츠팀", "글로벌팀",
    "리서치팀", "AI연구소", "보안팀", "인프라팀",
]

# 직급 풀
POSITIONS = [
    "사원", "주임", "대리", "과장", "차장", "부장",
    "팀장", "실장", "이사", "상무", "전무", "부사장", "사장",
    "선임연구원", "책임연구원", "수석연구원", "수석", "책임", "선임",
    "매니저", "시니어매니저", "디렉터",
]

# 도시 + 도로/건물 (가상 주소 생성용)
KOREAN_CITIES = [
    "서울특별시 강남구", "서울특별시 서초구", "서울특별시 마포구", "서울특별시 종로구",
    "서울특별시 영등포구", "서울특별시 송파구", "서울특별시 성동구",
    "경기도 성남시 분당구", "경기도 수원시 영통구", "경기도 고양시 일산서구",
    "부산광역시 해운대구", "부산광역시 수영구",
    "인천광역시 연수구", "대전광역시 유성구", "대구광역시 수성구",
    "광주광역시 북구", "광주광역시 서구",
    "울산광역시 남구", "세종특별자치시", "제주특별자치도 제주시",
]
ROAD_NAMES = [
    "테헤란로", "강남대로", "올림픽로", "양재대로", "센트럴로", "디지털로",
    "판교역로", "성남대로", "광교로", "월드컵로", "마포대로", "여의대로",
    "충무로", "을지로", "종로", "광화문로", "한강대로",
]


def fake_phone_office():
    """02 또는 0X 지역 + 0000-XXXX (안전대역)"""
    area = random.choice(["02", "031", "032", "051", "053", "062", "070"])
    if area == "02":
        return f"{area}-0{random.randint(100,999)}-{random.randint(1000,9999)}"
    return f"{area}-0{random.randint(100,999)}-{random.randint(1000,9999)}"


def fake_phone_mobile():
    """010-0000-XXXX (영화/드라마 안전대역)"""
    # 010-0XXX-XXXX 범위로 (실제 사용 안 되는 prefix 위주)
    return f"010-0{random.randint(100,999)}-{random.randint(1000,9999)}"


def fake_fax():
    return fake_phone_office()


def fake_address():
    city = random.choice(KOREAN_CITIES)
    road = random.choice(ROAD_NAMES)
    num = random.randint(1, 999)
    suffix = random.choice([
        f"{random.randint(1,30)}층",
        f"{chr(65+random.randint(0,5))}동 {random.randint(100,999)}호",
        f"{random.randint(1,20)}동 {random.randint(100,1500)}호",
        "",
    ])
    if suffix:
        return f"{city} {road} {num} {suffix}"
    return f"{city} {road} {num}"


def fake_person():
    surname = random.choice(KOREAN_SURNAMES)
    given = random.choice(KOREAN_GIVEN_NAMES)
    return surname + given


def fake_company():
    return random.choice(COMPANY_POOL).copy()


def fake_email_user():
    """user000~user9999 패턴 (실제 인물 식별 회피)"""
    return f"user{random.randint(1,9999):03d}"


def fake_position():
    return random.choice(POSITIONS)


def fake_department():
    return random.choice(DEPARTMENTS)
