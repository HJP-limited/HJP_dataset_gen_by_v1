"""
명함 합성기 코어
- 16개 레이아웃(8 base × selected visual) × 20장 = 320장 생성
- 어노테이션은 첨부된 bizcard_00000.json 형식과 동일하게 출력
- 300dpi, 90×50mm(가로) / 50×90mm(세로) → 픽셀 사이즈
"""
import os
import json
import math
import random
from PIL import Image, ImageDraw

from catalog import (
    FONT_PROFILES, COLOR_THEMES, NOTO_KR_INDEX,
    fake_phone_office, fake_phone_mobile, fake_fax, fake_address,
    fake_person, fake_company, fake_email_user, fake_position, fake_department,
)
from layouts import SELECTED_LAYOUTS, get_composite_layout
from visual_elements import (
    hex_to_rgb, hex_to_rgba, draw_background, draw_decorative_shapes,
    make_abstract_logo_image, paste_logo_with_initial, draw_qr_code_visual,
)
from text_render import get_font, draw_text_with_bbox, measure_text


# =============================================================================
# 캔버스 사이즈 - 300 DPI 기준
# =============================================================================
DPI = 300
MM_PER_INCH = 25.4

def mm_to_px(mm, dpi=DPI):
    return int(round(mm / MM_PER_INCH * dpi))


# 90 x 50 mm @ 300 DPI = 1063 x 591 px (첨부 JSON과 일치)
H_CARD_W_PX = mm_to_px(90)   # 1063
H_CARD_H_PX = mm_to_px(50)   # 591
V_CARD_W_PX = mm_to_px(50)   # 591
V_CARD_H_PX = mm_to_px(90)   # 1063


# =============================================================================
# 필드 클래스 ID (첨부된 어노테이션과 동일하게)
# =============================================================================
FIELD_CLASS_IDS = {
    "name":       0,
    "company":    1,
    "position":   2,
    "department": 3,
    "phone":      4,
    "mobile":     5,
    "fax":        6,
    "email":      7,
    "address":    8,
    "website":    9,
    "tagline":   10,
}


# =============================================================================
# 폰트 사이즈 매핑 - mm를 px로 환산 시 너무 작아지므로 px 직접 지정
# 300DPI 기준 1pt ≈ 4.166px
# =============================================================================
def pt_to_px(pt):
    return int(round(pt * DPI / 72))


# 명함의 표준 폰트 크기 (300 DPI 기준 px 환산)
FONT_SIZE_RANGES_PX = {
    "name":       (pt_to_px(13), pt_to_px(20)),   # ~54-83px
    "company":    (pt_to_px(8),  pt_to_px(12)),   # ~33-50px
    "position":   (pt_to_px(7),  pt_to_px(9)),
    "department": (pt_to_px(7),  pt_to_px(9)),
    "phone":      (pt_to_px(7),  pt_to_px(9)),
    "mobile":     (pt_to_px(7),  pt_to_px(9)),
    "fax":        (pt_to_px(7),  pt_to_px(9)),
    "email":      (pt_to_px(7),  pt_to_px(9)),
    "address":    (pt_to_px(6.5),pt_to_px(8.5)),
    "website":    (pt_to_px(7),  pt_to_px(9)),
    "tagline":    (pt_to_px(7),  pt_to_px(9)),
}


# =============================================================================
# 필드 정보 생성기
# =============================================================================
def generate_card_content(seed=None):
    """
    합성 명함 한 장에 들어갈 정보를 생성한다.
    - 필수 필드: name, mobile, email, company  (모든 명함에 반드시 포함)
    - 선택 필드: position, department, phone, fax, address, website, tagline
                각각 일정 확률로 포함
    """
    if seed is not None:
        random.seed(seed)

    company = fake_company()
    name = fake_person()
    email_user = fake_email_user()

    fields = {
        # 필수
        "company": {"required": True,  "value": company["ko"], "data": company},
        "name":    {"required": True,  "value": name},
        "mobile":  {"required": True,  "value": fake_phone_mobile()},
        "email":   {"required": True,  "value": f"{email_user}@{company['domain']}"},
        # 선택 (확률적 포함)
        "position":   {"required": False, "value": fake_position()},
        "department": {"required": False, "value": fake_department()},
        "phone":      {"required": False, "value": fake_phone_office()},
        "fax":        {"required": False, "value": fake_fax()},
        "address":    {"required": False, "value": fake_address()},
        "website":    {"required": False, "value": company["domain"]},
        "tagline":    {"required": False, "value": ""},  # 사용 안 함 (옵션)
    }

    # 선택 필드 포함 여부 결정
    include_probs = {
        "position":   0.85,
        "department": 0.70,
        "phone":      0.75,
        "fax":        0.20,   # 팩스는 감소 추세
        "address":    0.85,
        "website":    0.80,
        "tagline":    0.0,    # 일단 사용 안 함
    }

    selected = {}
    for k, info in fields.items():
        if info["required"]:
            selected[k] = info
        else:
            if random.random() < include_probs.get(k, 0.0):
                selected[k] = info

    return selected, company


# =============================================================================
# 어노테이션 빌더
# =============================================================================
def build_field_annotation(image_id, idx, field_class, rendered_text, canonical_text,
                            bbox, reading_order, language="ko"):
    field_id = f"{image_id}_{idx:03d}"
    line_id = f"{field_id}_l01"
    block_id = f"{field_id}_b01"
    bbox_int = [int(round(v)) for v in bbox]
    return {
        "field_id":       field_id,
        "field_class":    field_class,
        "field_class_id": FIELD_CLASS_IDS[field_class],
        "rendered_text":  rendered_text,
        "canonical_text": canonical_text,
        "bbox_xyxy":      bbox_int,
        "bbox_size":      {
            "width":  bbox_int[2] - bbox_int[0],
            "height": bbox_int[3] - bbox_int[1],
        },
        "reading_order":  reading_order,
        "line_ids":       [line_id],
        "line_boxes":     [bbox_int],
        "block_id":       block_id,
        "language":       language,
    }


# =============================================================================
# 레이아웃별 렌더 함수
# =============================================================================
# 각 레이아웃 함수 시그니처:
#   def render_LXX(card_img, draw, content, theme, font_profile, image_id) -> list[field_annot]
#
# - 텍스트 영역 배치
# - 시각 요소(로고/QR 등)는 별도로 처리되어 이 함수에서는 텍스트 어노테이션만 생성

def _format_phone_text(label, num):
    return f"{label} {num}"


def _render_line_block(card_img, draw, lines, start_xy, anchor, font_color, max_width=None):
    """
    여러 줄의 텍스트(label, font, canonical) 리스트를 위에서 아래로 그려나간다.
    
    lines: [{"text": str, "font": ImageFont, "canonical": str, "field_class": str}, ...]
    start_xy: (x, y)
    anchor: "lt" / "rt" / "mt" - 모든 줄에 동일 적용
    max_width: None이거나 픽셀 폭. 지정 시 텍스트가 이를 초과하면 자동 줄바꿈.
    
    Returns: [(field_class, rendered_text, canonical_text, bbox_xyxy), ...]
    
    줄바꿈된 경우, line_boxes는 여러 줄의 bbox 리스트로 들어가고
    bbox_xyxy는 전체를 감싸는 union bbox.
    """
    out = []
    x, y = start_xy
    for line in lines:
        font = line["font"]
        text = line["text"]

        # 줄바꿈 필요한지 측정
        bbox0 = draw.textbbox((0, 0), text, font=font)
        text_w = bbox0[2] - bbox0[0]

        if max_width is not None and text_w > max_width:
            # 단어 단위로 줄바꿈 (공백 기준)
            words = text.split(" ")
            wrapped_lines = []
            cur = ""
            for w in words:
                trial = (cur + " " + w).strip() if cur else w
                trial_bbox = draw.textbbox((0, 0), trial, font=font)
                if trial_bbox[2] - trial_bbox[0] > max_width and cur:
                    wrapped_lines.append(cur)
                    cur = w
                else:
                    cur = trial
            if cur:
                wrapped_lines.append(cur)

            # 각 줄 그리기 + bbox 모으기
            line_bboxes = []
            for wline in wrapped_lines:
                _, line_bb = draw_text_with_bbox(
                    card_img, draw, wline, x, y, font, font_color, anchor=anchor
                )
                line_bboxes.append(line_bb)
                y = line_bb[3] + max(2, int(font.size * 0.15))

            # union bbox
            ux1 = min(b[0] for b in line_bboxes)
            uy1 = min(b[1] for b in line_bboxes)
            ux2 = max(b[2] for b in line_bboxes)
            uy2 = max(b[3] for b in line_bboxes)
            out.append((
                line["field_class"],
                text,  # rendered_text는 원본 (라벨 포함)
                line["canonical"],
                [ux1, uy1, ux2, uy2],
            ))
            y += line["line_gap"] - max(2, int(font.size * 0.15))
        else:
            # 한 줄로 그리기
            rendered_text, drawn_bbox = draw_text_with_bbox(
                card_img, draw, text, x, y, font, font_color, anchor=anchor
            )
            out.append((
                line["field_class"],
                rendered_text,
                line["canonical"],
                drawn_bbox,
            ))
            y = drawn_bbox[3] + line["line_gap"]
    return out


def _make_contact_lines(content, fonts, color_text, color_muted):
    """
    전화/모바일/팩스/이메일/주소/웹사이트의 텍스트 라인 정보를 만들어 반환.
    - 어노테이션의 rendered_text는 라벨 포함 ("Tel. 02.0xxx-xxxx")
    - canonical_text는 숫자/주소 본체만
    """
    body_font = fonts["body"]
    body_height_unit = body_font.size  # 폰트 크기를 line_gap 기준으로 사용
    line_gap = max(4, int(body_height_unit * 0.35))

    out = []
    if "phone" in content:
        out.append({
            "field_class": "phone",
            "text":        f"Tel. {content['phone']['value']}",
            "canonical":   content["phone"]["value"],
            "font":        body_font,
            "line_gap":    line_gap,
        })
    if "mobile" in content:
        out.append({
            "field_class": "mobile",
            "text":        f"M. {content['mobile']['value']}",
            "canonical":   content["mobile"]["value"],
            "font":        body_font,
            "line_gap":    line_gap,
        })
    if "fax" in content:
        out.append({
            "field_class": "fax",
            "text":        f"F. {content['fax']['value']}",
            "canonical":   content["fax"]["value"],
            "font":        body_font,
            "line_gap":    line_gap,
        })
    if "email" in content:
        out.append({
            "field_class": "email",
            "text":        f"E-mail {content['email']['value']}",
            "canonical":   content["email"]["value"],
            "font":        body_font,
            "line_gap":    line_gap,
        })
    if "address" in content:
        out.append({
            "field_class": "address",
            "text":        f"Address {content['address']['value']}",
            "canonical":   content["address"]["value"],
            "font":        body_font,
            "line_gap":    line_gap,
        })
    if "website" in content:
        out.append({
            "field_class": "website",
            "text":        f"Web {content['website']['value']}",
            "canonical":   content["website"]["value"],
            "font":        body_font,
            "line_gap":    line_gap,
        })
    return out


# -----------------------------------------------------------------------------
# 레이아웃 L01: 좌측정렬 (가로형) - 표준 미니멀
# -----------------------------------------------------------------------------
def render_L01(card_img, draw, content, theme, fonts, image_id):
    """좌측 정렬 - 좌상단에 회사명 / 큰 이름 / 직급 / 하단에 연락처 블록"""
    W, H = card_img.size
    margin = mm_to_px(5)  # 5mm 여백

    color_text = theme["text"]
    color_muted = theme["muted"]
    color_accent = theme["accent"]

    raw_results = []  # (field_class, rendered, canonical, bbox)

    # 1. 좌상단 회사명
    cur_y = margin + mm_to_px(2)
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            margin, cur_y, fonts["title"], color_accent, anchor="lt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))
        cur_y = bbox[3] + mm_to_px(2)

    # 2. 큰 이름 (가장 강조)
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        margin, cur_y, fonts["name"], color_text, anchor="lt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y = bbox[3] + mm_to_px(1)

    # 3. 직급/부서 - 같은 줄 또는 두 줄
    if "department" in content or "position" in content:
        parts = []
        if "department" in content:
            parts.append(content["department"]["value"])
        if "position" in content:
            parts.append(content["position"]["value"])
        # 부서/직급을 별도 라인으로 (어노테이션 위해)
        for fc in ["department", "position"]:
            if fc in content:
                rendered, bbox = draw_text_with_bbox(
                    card_img, draw, content[fc]["value"],
                    margin, cur_y, fonts["title"], color_muted, anchor="lt"
                )
                raw_results.append((fc, rendered, content[fc]["value"], bbox))
                cur_y = bbox[3] + mm_to_px(0.5)

    # 4. 하단 연락처 블록 (좌하단 영역)
    contact_lines = _make_contact_lines(content, fonts, color_text, color_muted)
    # 하단부터 위로 쌓아도 되지만 여기서는 직급 다음부터 그냥 이어서
    cur_y += mm_to_px(2)
    available_width = W - margin * 2
    line_results = _render_line_block(
        card_img, draw, contact_lines, (margin, cur_y), "lt", color_text,
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L02: 좌상단 회사 + 우측 인물 정보 (가로형)
# -----------------------------------------------------------------------------
def render_L02(card_img, draw, content, theme, fonts, image_id):
    """좌상단에 회사 영역, 명함 우측에 인물 정보, 하단 좌측에 연락처"""
    W, H = card_img.size
    margin = mm_to_px(5)
    color_text = theme["text"]
    color_muted = theme["muted"]
    color_accent = theme["accent"]
    raw_results = []

    # 1. 좌상단 회사명 (작게)
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            margin, margin + mm_to_px(2), fonts["title"], color_accent, anchor="lt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))

    # 2. 우측에 이름 (큰 글씨, 우측 정렬)
    right_x = W - margin
    cur_y = margin + mm_to_px(2)
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        right_x, cur_y, fonts["name"], color_text, anchor="rt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y = bbox[3] + mm_to_px(1)

    # 3. 우측 직급/부서
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                right_x, cur_y, fonts["title"], color_muted, anchor="rt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y = bbox[3] + mm_to_px(0.5)

    # 4. 하단 연락처 (좌측 정렬)
    contact_lines = _make_contact_lines(content, fonts, color_text, color_muted)
    contact_y_start = H - margin - mm_to_px(2) - len(contact_lines) * (fonts["body"].size + mm_to_px(1))
    contact_y_start = max(contact_y_start, cur_y + mm_to_px(3))
    available_width = W - margin * 2
    line_results = _render_line_block(
        card_img, draw, contact_lines, (margin, contact_y_start), "lt", color_text,
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L03: 상단 컬러 밴드 + 하단 텍스트 (가로형)
# -----------------------------------------------------------------------------
def render_L03(card_img, draw, content, theme, fonts, image_id):
    """
    상단 35% 영역을 강조색 밴드로 채우고 그 안에 회사명 + 이름,
    하단 65%에 직급/부서/연락처
    """
    W, H = card_img.size
    margin = mm_to_px(5)
    raw_results = []

    band_h = int(H * 0.40)
    # 강조색 밴드 그리기
    draw.rectangle([0, 0, W, band_h], fill=hex_to_rgb(theme["accent"]))

    # 밴드 안의 텍스트 색상은 배경 대비로 결정
    accent_rgb = hex_to_rgb(theme["accent"])
    band_is_dark = (accent_rgb[0] + accent_rgb[1] + accent_rgb[2]) / 3 < 128
    band_text_color = (255, 255, 255) if band_is_dark else (15, 15, 15)
    band_text_hex = "#{:02X}{:02X}{:02X}".format(*band_text_color)

    color_text = theme["text"]
    color_muted = theme["muted"]

    # 1. 회사명 (밴드 좌상단)
    cur_y = margin
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            margin, cur_y, fonts["title"], band_text_hex, anchor="lt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))
        cur_y = bbox[3] + mm_to_px(2)

    # 2. 이름 (밴드 안 큰 글씨)
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        margin, cur_y, fonts["name"], band_text_hex, anchor="lt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))

    # 3. 하단 영역 - 직급/부서/연락처
    cur_y = band_h + margin
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                margin, cur_y, fonts["title"], color_text, anchor="lt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y = bbox[3] + mm_to_px(0.5)

    cur_y += mm_to_px(1)
    contact_lines = _make_contact_lines(content, fonts, color_text, color_muted)
    available_width = W - margin * 2
    line_results = _render_line_block(
        card_img, draw, contact_lines, (margin, cur_y), "lt", color_text,
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L04: 좌측 컬러 스트립 + 우측 정보 (가로형)
# -----------------------------------------------------------------------------
def render_L04(card_img, draw, content, theme, fonts, image_id):
    """좌측 28% 컬러 스트립(회사명 세로로?), 우측 72%에 인물 정보 + 연락처"""
    W, H = card_img.size
    raw_results = []

    strip_w = int(W * 0.28)
    margin = mm_to_px(4)

    # 좌측 컬러 스트립
    draw.rectangle([0, 0, strip_w, H], fill=hex_to_rgb(theme["accent"]))

    accent_rgb = hex_to_rgb(theme["accent"])
    band_is_dark = (accent_rgb[0] + accent_rgb[1] + accent_rgb[2]) / 3 < 128
    strip_text_color = (255, 255, 255) if band_is_dark else (15, 15, 15)
    strip_text_hex = "#{:02X}{:02X}{:02X}".format(*strip_text_color)

    # 1. 스트립 안 회사명 (위에서 아래) - 스트립 배경과 대비되는 색으로
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            margin, margin + mm_to_px(2), fonts["title"], strip_text_hex, anchor="lt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))

    # 2. 우측 영역
    right_margin = strip_w + mm_to_px(5)
    cur_y = margin + mm_to_px(3)

    # 이름
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        right_margin, cur_y, fonts["name"], theme["text"], anchor="lt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y = bbox[3] + mm_to_px(1)

    # 직급/부서
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                right_margin, cur_y, fonts["title"], theme["muted"], anchor="lt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y = bbox[3] + mm_to_px(0.5)

    # 연락처
    cur_y += mm_to_px(2)
    contact_lines = _make_contact_lines(content, fonts, theme["text"], theme["muted"])
    available_width = W - right_margin - mm_to_px(4)
    line_results = _render_line_block(
        card_img, draw, contact_lines, (right_margin, cur_y), "lt", theme["text"],
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L05: 중앙 정렬 (가로형)
# -----------------------------------------------------------------------------
def render_L05(card_img, draw, content, theme, fonts, image_id):
    """모든 정보를 중앙 정렬"""
    W, H = card_img.size
    cx = W // 2
    margin = mm_to_px(4)
    raw_results = []

    cur_y = margin + mm_to_px(2)

    # 1. 회사명 (위)
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            cx, cur_y, fonts["title"], theme["accent"], anchor="mt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))
        cur_y = bbox[3] + mm_to_px(3)

    # 2. 이름 (가운데, 큰 글씨)
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        cx, cur_y, fonts["name"], theme["text"], anchor="mt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y = bbox[3] + mm_to_px(1)

    # 3. 직급/부서 (한 줄)
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                cx, cur_y, fonts["title"], theme["muted"], anchor="mt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y = bbox[3] + mm_to_px(0.5)

    # 4. 연락처 (가운데 정렬)
    cur_y += mm_to_px(2)
    contact_lines = _make_contact_lines(content, fonts, theme["text"], theme["muted"])
    for line in contact_lines:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, line["text"], cx, cur_y, line["font"], theme["text"], anchor="mt"
        )
        raw_results.append((line["field_class"], rendered, line["canonical"], bbox))
        cur_y = bbox[3] + line["line_gap"]

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L06: 좌우 두칸 분할 (가로형)
# -----------------------------------------------------------------------------
def render_L06(card_img, draw, content, theme, fonts, image_id):
    """좌측: 회사명/이름/직급, 우측: 연락처"""
    W, H = card_img.size
    margin = mm_to_px(5)
    raw_results = []

    left_x = margin
    # 우측 컬럼 시작 위치 - 이메일/주소가 우측 끝까지 가야 하므로 좀 더 왼쪽에서 시작
    right_x = int(W * 0.46)
    cur_y_left = margin + mm_to_px(3)

    # 1. 좌측 회사명
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            left_x, cur_y_left, fonts["title"], theme["accent"], anchor="lt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))
        cur_y_left = bbox[3] + mm_to_px(3)

    # 2. 좌측 이름
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        left_x, cur_y_left, fonts["name"], theme["text"], anchor="lt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y_left = bbox[3] + mm_to_px(1)

    # 3. 좌측 직급/부서
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                left_x, cur_y_left, fonts["title"], theme["muted"], anchor="lt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y_left = bbox[3] + mm_to_px(0.5)

    # 4. 우측 연락처 - 우상단 로고 회피하기 위해 시작 y를 살짝 아래로
    cur_y_right = margin + mm_to_px(15)  # 로고가 12mm 영역까지 차지하므로
    contact_lines = _make_contact_lines(content, fonts, theme["text"], theme["muted"])
    available_width = W - right_x - margin
    line_results = _render_line_block(
        card_img, draw, contact_lines, (right_x, cur_y_right), "lt", theme["text"],
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L07: 세로형 - 상단 회사 + 중앙 이름 + 하단 연락처
# -----------------------------------------------------------------------------
def render_L07(card_img, draw, content, theme, fonts, image_id, visual_preset=None):
    """세로형 명함 - 상단 회사명, 중앙 큰 이름/직급, 하단 연락처 좌측정렬"""
    W, H = card_img.size
    margin = mm_to_px(5)
    cx = W // 2
    raw_results = []

    # 1. 상단 회사명 (가운데 정렬) - 로고가 있을 때만 아래로
    has_logo = visual_preset and visual_preset.get("logo", False) if visual_preset else False
    cur_y = mm_to_px(15) if has_logo else mm_to_px(8)
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            cx, cur_y, fonts["title"], theme["accent"], anchor="mt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))

    # 2. 중앙 이름 (큰 글씨)
    cur_y = int(H * 0.25)
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        cx, cur_y, fonts["name"], theme["text"], anchor="mt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y = bbox[3] + mm_to_px(2)

    # 3. 부서/직급 (가운데)
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                cx, cur_y, fonts["title"], theme["muted"], anchor="mt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y = bbox[3] + mm_to_px(1)

    # 4. 하단 연락처 (좌측 정렬)
    contact_y_start = int(H * 0.55)
    contact_lines = _make_contact_lines(content, fonts, theme["text"], theme["muted"])
    available_width = W - margin * 2
    line_results = _render_line_block(
        card_img, draw, contact_lines, (margin, contact_y_start), "lt", theme["text"],
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# -----------------------------------------------------------------------------
# 레이아웃 L08: 세로형 - 모두 좌측 정렬
# -----------------------------------------------------------------------------
def render_L08(card_img, draw, content, theme, fonts, image_id, visual_preset=None):
    """세로형 - 위에서부터 회사/이름/직급/연락처 모두 좌측 정렬"""
    W, H = card_img.size
    margin = mm_to_px(5)
    raw_results = []

    # 로고가 좌상단에 들어갈 때만 회사명을 아래로 내림
    has_logo = visual_preset and visual_preset.get("logo", False) if visual_preset else False
    if has_logo:
        cur_y = margin + mm_to_px(15)
    else:
        cur_y = margin + mm_to_px(5)

    # 1. 회사명
    if "company" in content:
        rendered, bbox = draw_text_with_bbox(
            card_img, draw, content["company"]["value"],
            margin, cur_y, fonts["title"], theme["accent"], anchor="lt"
        )
        raw_results.append(("company", rendered, content["company"]["value"], bbox))
        cur_y = bbox[3] + mm_to_px(4)

    # 2. 이름 (큰 글씨)
    rendered, bbox = draw_text_with_bbox(
        card_img, draw, content["name"]["value"],
        margin, cur_y, fonts["name"], theme["text"], anchor="lt"
    )
    raw_results.append(("name", rendered, content["name"]["value"], bbox))
    cur_y = bbox[3] + mm_to_px(2)

    # 3. 부서/직급
    for fc in ["department", "position"]:
        if fc in content:
            rendered, bbox = draw_text_with_bbox(
                card_img, draw, content[fc]["value"],
                margin, cur_y, fonts["title"], theme["muted"], anchor="lt"
            )
            raw_results.append((fc, rendered, content[fc]["value"], bbox))
            cur_y = bbox[3] + mm_to_px(1)

    # 4. 연락처
    cur_y += mm_to_px(3)
    contact_lines = _make_contact_lines(content, fonts, theme["text"], theme["muted"])
    available_width = W - margin * 2
    line_results = _render_line_block(
        card_img, draw, contact_lines, (margin, cur_y), "lt", theme["text"],
        max_width=available_width
    )
    raw_results.extend(line_results)

    return raw_results


# 레이아웃 ID → 렌더 함수 매핑
RENDER_FUNCS = {
    "L01": render_L01,
    "L02": render_L02,
    "L03": render_L03,
    "L04": render_L04,
    "L05": render_L05,
    "L06": render_L06,
    "L07": render_L07,
    "L08": render_L08,
}
