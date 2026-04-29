"""
명함 합성 메인 엔트리포인트
- 16개 SELECTED_LAYOUTS × 20장 = 320장 생성
- 어노테이션은 첨부된 bizcard_00000.json과 동일한 형식으로 출력
"""
import os
import json
import random
import argparse

from PIL import Image, ImageDraw

from catalog import (
    FONT_PROFILES, COLOR_THEMES,
)
from layouts import SELECTED_LAYOUTS, get_composite_layout
from visual_elements import (
    hex_to_rgb, hex_to_rgba, draw_background, draw_decorative_shapes,
    make_abstract_logo_image, paste_logo_with_initial, draw_qr_code_visual,
)
from text_render import get_font, draw_text_with_bbox
from card_renderer import (
    H_CARD_W_PX, H_CARD_H_PX, V_CARD_W_PX, V_CARD_H_PX,
    FONT_SIZE_RANGES_PX, FIELD_CLASS_IDS,
    generate_card_content, build_field_annotation,
    RENDER_FUNCS, mm_to_px, pt_to_px,
)


# =============================================================================
# 폰트 사이즈 결정 - 레이아웃에 따라 적절히 스케일링
# =============================================================================
def make_font_set(font_profile_name, base_layout_id, name_size_pt=None):
    """
    레이아웃에 따라 폰트 패밀리(이름/타이틀/본문)에 적용할 사이즈를 결정.
    
    Returns: {"name": Font, "title": Font, "body": Font, "accent": Font}
    """
    profile = FONT_PROFILES[font_profile_name]

    # 기본 명함 폰트 사이즈 (300DPI 기준 px)
    # 가이드 기준: 이름 10-16pt, 회사/직급 8-10pt, 본문 7-9pt
    name_pt = name_size_pt or random.choice([12, 13, 14, 15])
    title_pt = random.choice([7.5, 8, 8.5])
    body_pt = random.choice([7, 7.5, 8])
    accent_pt = body_pt - 0.5

    # 세로형 (L07, L08)은 이름 크기를 약간 더 키움 (세로형은 공간이 더 있음)
    if base_layout_id in ("L07", "L08"):
        name_pt = max(name_pt, 16)

    # 중앙정렬(L05)도 이름 크기를 약간 더 키움
    if base_layout_id == "L05":
        name_pt = max(name_pt, 14)

    return {
        "name":   get_font(profile["name_font"],   pt_to_px(name_pt)),
        "title":  get_font(profile["title_font"],  pt_to_px(title_pt)),
        "body":   get_font(profile["body_font"],   pt_to_px(body_pt)),
        "accent": get_font(profile["accent_font"], pt_to_px(accent_pt)),
        "_size_meta": {
            "name_pt":   name_pt,
            "title_pt":  title_pt,
            "body_pt":   body_pt,
            "accent_pt": accent_pt,
        },
    }


# =============================================================================
# 색상 테마 선정 - 레이아웃에 따른 적합도 고려
# =============================================================================
def pick_theme_for_layout(base_layout_id, visual_preset_id):
    """
    레이아웃과 시각 요소에 따라 적합한 색상 테마 후보군에서 랜덤 선택.
    배경색과 글자색이 다른 것을 보장.
    """
    # 모든 테마는 catalog에서 bg/text가 다르게 정의되어 있으므로 그대로 사용 가능
    theme_keys = list(COLOR_THEMES.keys())

    # 기본 가중치
    # - L03(상단밴드), L04(좌측스트립)는 강조색이 큰 영역에 사용되므로
    #   너무 어두운 배경 + 너무 어두운 강조는 피하는 게 좋음
    # - 다른 레이아웃은 자유롭게
    chosen_key = random.choice(theme_keys)
    theme = COLOR_THEMES[chosen_key].copy()
    theme["_name"] = chosen_key

    # 안전장치: bg와 text가 같으면 다른 테마로 재선택
    safety = 0
    while theme["bg"].lower() == theme["text"].lower() and safety < 5:
        chosen_key = random.choice(theme_keys)
        theme = COLOR_THEMES[chosen_key].copy()
        theme["_name"] = chosen_key
        safety += 1

    return theme


# =============================================================================
# 시각 요소 추가
# =============================================================================
def add_background_pattern(card_img, theme, visual_preset):
    """배경 패턴만 추가 (텍스트 렌더 BEFORE에 호출)"""
    W, H = card_img.size
    if not visual_preset["bg_pattern"]:
        return None

    pattern_type = random.choice(["dots", "lines_h", "lines_v", "grid", "diagonal", "triangles"])
    pat_img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pat_draw = ImageDraw.Draw(pat_img, "RGBA")

    bg_rgb = hex_to_rgb(theme["bg"])
    is_dark = (bg_rgb[0] + bg_rgb[1] + bg_rgb[2]) / 3 < 128
    pat_color = tuple(min(255, c + 22) for c in bg_rgb) if is_dark else tuple(max(0, c - 22) for c in bg_rgb)
    pat_color_rgba = pat_color + (255,)

    if pattern_type == "dots":
        spacing = random.randint(28, 48)
        radius = random.randint(2, 4)
        for y in range(spacing // 2, H, spacing):
            for x in range(spacing // 2, W, spacing):
                pat_draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=pat_color_rgba)
    elif pattern_type == "lines_h":
        spacing = random.randint(18, 36)
        for y in range(0, H, spacing):
            pat_draw.rectangle([0, y, W, y + 1], fill=pat_color_rgba)
    elif pattern_type == "lines_v":
        spacing = random.randint(18, 36)
        for x in range(0, W, spacing):
            pat_draw.rectangle([x, 0, x + 1, H], fill=pat_color_rgba)
    elif pattern_type == "grid":
        spacing = random.randint(30, 50)
        for y in range(0, H, spacing):
            pat_draw.rectangle([0, y, W, y + 1], fill=pat_color_rgba)
        for x in range(0, W, spacing):
            pat_draw.rectangle([x, 0, x + 1, H], fill=pat_color_rgba)
    elif pattern_type == "diagonal":
        spacing = random.randint(24, 40)
        for k in range(-H, W, spacing):
            pat_draw.line([(k, 0), (k + H, H)], fill=pat_color_rgba, width=1)
    elif pattern_type == "triangles":
        size = random.randint(40, 70)
        for y in range(-size, H + size, size):
            for x in range(-size, W + size, size * 2):
                offset = (size if (y // size) % 2 else 0)
                pts = [
                    (x + offset, y),
                    (x + offset + size, y),
                    (x + offset + size // 2, y + size),
                ]
                pat_draw.polygon(pts, outline=pat_color_rgba, width=1)

    card_img.alpha_composite(pat_img)
    return pattern_type


def add_logo_qr_shape(card_img, theme, base_layout_id, visual_preset,
                     company_data, font_profile_name, image_id):
    """로고/QR/도형을 텍스트 렌더 AFTER에 그린다 (밴드/스트립 위에 표시되도록)."""
    W, H = card_img.size
    draw = ImageDraw.Draw(card_img)

    meta = {
        "decorative_shapes": [],
        "logo": None,
        "qr_code": None,
    }

    # ---- 도형 장식 (텍스트 후. 다만 텍스트와 충돌 없는 영역에) ----
    if visual_preset["shape"]:
        kinds_used = []

        # 좌상단/우상단 코너에 작은 액센트 도형
        # 단, L03(상단밴드)과 L04(좌측스트립)는 이미 색상영역이 있어서
        # 코너 도형이 잘 보이도록 위치 다르게 결정
        if base_layout_id == "L03":
            # 상단 밴드 안 우상단에 작은 흰색 도형
            band_h = int(H * 0.40)
            sz = mm_to_px(5)
            cx = W - mm_to_px(5) - sz
            cy = mm_to_px(5)
            shape_kind = random.choice(["ring", "circle"])
            if shape_kind == "circle":
                draw.ellipse([cx, cy, cx + sz, cy + sz], outline=(255, 255, 255), width=mm_to_px(0.5))
            else:
                draw.ellipse([cx, cy, cx + sz, cy + sz], outline=(255, 255, 255), width=mm_to_px(0.5))
            kinds_used.append(shape_kind + "_band_corner")

            # 하단 영역에 짧은 색상 막대
            bar_x = mm_to_px(5)
            bar_y = band_h + mm_to_px(3)
            bar_w = mm_to_px(15)
            bar_h = mm_to_px(0.6)
            draw.rectangle([bar_x, bar_y, bar_x + bar_w, bar_y + bar_h], fill=hex_to_rgb(theme["accent"]))
            kinds_used.append("section_divider")

        elif base_layout_id == "L04":
            # 좌측 스트립 안 작은 도형
            sz = mm_to_px(5)
            cx = mm_to_px(8)
            cy = H - mm_to_px(8) - sz
            shape_kind = random.choice(["ring", "circle", "square"])
            accent_rgb = hex_to_rgb(theme["accent"])
            band_is_dark = (accent_rgb[0] + accent_rgb[1] + accent_rgb[2]) / 3 < 128
            stripe_text_color = (255, 255, 255) if band_is_dark else (15, 15, 15)
            if shape_kind == "circle":
                draw.ellipse([cx, cy, cx + sz, cy + sz], fill=stripe_text_color)
            elif shape_kind == "square":
                draw.rectangle([cx, cy, cx + sz, cy + sz], fill=stripe_text_color)
            else:
                draw.ellipse([cx, cy, cx + sz, cy + sz], outline=stripe_text_color, width=mm_to_px(0.5))
            kinds_used.append(shape_kind + "_strip_bottom")

        else:
            # 일반 가로형/세로형 - 우상단 또는 카드 가장자리에 작은 액센트
            sz = mm_to_px(5)
            cx = W - mm_to_px(5) - sz
            cy = mm_to_px(5)
            shape_kind = random.choice(["circle", "square", "ring"])
            if shape_kind == "circle":
                draw.ellipse([cx, cy, cx + sz, cy + sz], fill=hex_to_rgb(theme["accent"]))
            elif shape_kind == "square":
                draw.rectangle([cx, cy, cx + sz, cy + sz], fill=hex_to_rgb(theme["accent"]))
            else:
                draw.ellipse([cx, cy, cx + sz, cy + sz], outline=hex_to_rgb(theme["accent"]), width=mm_to_px(0.5))
            kinds_used.append(shape_kind + "_corner_accent")

            # 좌측 짧은 색상 막대 (텍스트 영역 위에)
            if random.random() < 0.5:
                bar_x = mm_to_px(5)
                bar_y = mm_to_px(3)
                bar_w = mm_to_px(10)
                bar_h_px = mm_to_px(0.6)
                draw.rectangle([bar_x, bar_y, bar_x + bar_w, bar_y + bar_h_px], fill=hex_to_rgb(theme["accent"]))
                kinds_used.append("top_short_bar")

        meta["decorative_shapes"] = kinds_used

    # ---- 로고 ----
    if visual_preset["logo"]:
        # L03(밴드)에서는 로고 색상이 밴드와 똑같으면 안 보이므로 흰/검 컨테이너로
        logo_size = mm_to_px(11)

        # 로고 위치는 base_layout별로 다르게
        if base_layout_id == "L01":
            logo_pos = (W - logo_size - mm_to_px(5), mm_to_px(5))
        elif base_layout_id == "L02":
            # 회사명 옆 좌상단
            logo_pos = (mm_to_px(5), mm_to_px(12))
        elif base_layout_id == "L03":
            # 상단 밴드 안 우측. 컨테이너 색을 밴드 텍스트색과 동일하게 (밝은색 또는 어두운색)
            logo_pos = (W - logo_size - mm_to_px(5), mm_to_px(4))
        elif base_layout_id == "L04":
            # 좌측 스트립 안 - 회사명 아래쪽
            logo_pos = (mm_to_px(7), mm_to_px(18))
            logo_size = mm_to_px(10)
        elif base_layout_id == "L05":
            # 중앙정렬 - 로고 좌상단 작게
            logo_pos = (mm_to_px(5), mm_to_px(5))
            logo_size = mm_to_px(8)
        elif base_layout_id == "L06":
            logo_pos = (W - logo_size - mm_to_px(5), mm_to_px(5))
        elif base_layout_id == "L07":
            # 세로형 - 상단 가운데
            logo_pos = ((W - mm_to_px(10)) // 2, mm_to_px(2))
            logo_size = mm_to_px(10)
        elif base_layout_id == "L08":
            # 세로형 좌상단
            logo_pos = (mm_to_px(5), mm_to_px(5))
            logo_size = mm_to_px(10)
        else:
            logo_pos = (W - logo_size - mm_to_px(5), mm_to_px(5))

        initial = company_data["en"][0].upper() if company_data.get("en") else ""

        # L03/L04에서는 로고 컨테이너 색이 밴드/스트립과 다르게 보이도록
        # 임시 테마 변형
        logo_theme = theme.copy()
        if base_layout_id in ("L03", "L04"):
            # 밴드 색이 강조색이므로, 로고는 흰색/검정 컨테이너로
            accent_rgb = hex_to_rgb(theme["accent"])
            band_is_dark = (accent_rgb[0] + accent_rgb[1] + accent_rgb[2]) / 3 < 128
            if band_is_dark:
                logo_theme["accent"] = "#FFFFFF"
                logo_theme["text"] = theme["accent"]
            else:
                logo_theme["accent"] = "#1A1A1A"
                logo_theme["text"] = theme["accent"]

        logo_img, kind = make_abstract_logo_image(logo_size, logo_theme, initial)
        try:
            paste_logo_with_initial(
                card_img, logo_img, kind, logo_pos, logo_size, logo_theme,
                FONT_PROFILES[font_profile_name]["name_font"], initial=initial
            )
        except Exception:
            card_img.alpha_composite(logo_img, dest=logo_pos)

        meta["logo"] = {
            "kind":     kind,
            "position": [logo_pos[0], logo_pos[1], logo_pos[0] + logo_size, logo_pos[1] + logo_size],
            "size_px":  logo_size,
            "initial":  initial,
        }

    # ---- QR 코드 ----
    if visual_preset["qr"]:
        qr_target_size = mm_to_px(13)
        if base_layout_id in ("L07", "L08"):
            qr_x = (W - qr_target_size) // 2
            qr_y = H - qr_target_size - mm_to_px(5)
        else:
            qr_x = W - qr_target_size - mm_to_px(4)
            qr_y = H - qr_target_size - mm_to_px(4)

        qr_bbox, qr_actual_size = draw_qr_code_visual(
            card_img, qr_x, qr_y, qr_target_size, theme,
            seed=hash(image_id) & 0x7FFFFFFF
        )
        meta["qr_code"] = {
            "bbox":     [int(v) for v in qr_bbox],
            "encoded":  f"https://example.com/{image_id}",
        }

    return meta


# =============================================================================
# 한 장 합성
# =============================================================================
def synthesize_card(image_id, base_layout_id, visual_preset_id,
                    output_dir, seed=None):
    """
    한 장의 명함을 합성하고 (이미지파일 + JSON 어노테이션)으로 저장.
    """
    if seed is not None:
        random.seed(seed)

    composite = get_composite_layout(base_layout_id, visual_preset_id)
    visual_preset = {
        "bg_pattern": composite["uses_bg_pattern"],
        "shape":      composite["uses_shape"],
        "logo":       composite["uses_logo"],
        "qr":         composite["uses_qr"],
    }

    # 캔버스 생성
    if composite["orientation"] == "vertical":
        W, H = V_CARD_W_PX, V_CARD_H_PX
    else:
        W, H = H_CARD_W_PX, H_CARD_H_PX

    # 색상 테마 선택
    theme = pick_theme_for_layout(base_layout_id, visual_preset_id)

    # 폰트 프로파일 선택
    font_profile_name = random.choice(list(FONT_PROFILES.keys()))
    fonts = make_font_set(font_profile_name, base_layout_id)

    # 콘텐츠 생성
    content, company_data = generate_card_content()

    # 캔버스 - 알파 채널 사용 (로고 합성을 위해)
    card_img = Image.new("RGBA", (W, H), (255, 255, 255, 255))
    # 배경색 채우기
    draw_background(card_img, theme, pattern_type=None)  # solid bg first
    draw = ImageDraw.Draw(card_img)

    # 1) 배경 패턴 (텍스트 BEFORE - 단색 위에 패턴만 깔음)
    pattern_type = add_background_pattern(card_img, theme, visual_preset)

    # 2) 텍스트 렌더 (레이아웃별 함수 호출 - 밴드/스트립 같은 컬러영역도 여기서 그림)
    render_func = RENDER_FUNCS[base_layout_id]
    # L07/L08은 visual_preset에 따라 회사명 위치 조정
    if base_layout_id in ("L07", "L08"):
        raw_results = render_func(card_img, draw, content, theme, fonts, image_id, visual_preset=visual_preset)
    else:
        raw_results = render_func(card_img, draw, content, theme, fonts, image_id)

    # 3) 시각요소 (텍스트 AFTER - 로고/QR/도형이 텍스트와 밴드 위에 표시)
    visual_meta = add_logo_qr_shape(
        card_img, theme, base_layout_id, visual_preset,
        company_data, font_profile_name, image_id
    )
    visual_meta["background_pattern"] = pattern_type

    # 3) 어노테이션 빌드
    field_annotations = []
    for idx, (field_class, rendered, canonical, bbox) in enumerate(raw_results, start=1):
        ann = build_field_annotation(
            image_id=image_id,
            idx=idx,
            field_class=field_class,
            rendered_text=rendered,
            canonical_text=canonical,
            bbox=bbox,
            reading_order=idx,
            language="ko",
        )
        field_annotations.append(ann)

    # 4) 이미지 저장 (RGB 변환 후 JPEG, 300dpi 메타데이터)
    img_dir = os.path.join(output_dir, "images")
    ann_dir = os.path.join(output_dir, "annotations")
    os.makedirs(img_dir, exist_ok=True)
    os.makedirs(ann_dir, exist_ok=True)

    img_filename = f"bizcard_{image_id}.jpg"
    img_path = os.path.join(img_dir, img_filename)
    rgb_img = card_img.convert("RGB")
    rgb_img.save(img_path, "JPEG", quality=92, dpi=(300, 300))

    # 5) JSON 어노테이션 저장
    annotation = {
        "image_id":     image_id,
        "image_path":   f"images/{img_filename}",
        "image_size": {
            "width":  W,
            "height": H,
        },
        "card_orientation": composite["orientation"],
        "card_spec_mm": {
            "width":  50 if composite["orientation"] == "vertical" else 90,
            "height": 90 if composite["orientation"] == "vertical" else 50,
        },
        "dpi": DPI if False else 300,
        "layout_type": composite["base_layout_name"],
        "layout_composite_id": composite["composite_id"],
        "layout_features": {
            "uses_bg_pattern": visual_preset["bg_pattern"],
            "uses_shape":      visual_preset["shape"],
            "uses_logo":       visual_preset["logo"],
            "uses_qr":         visual_preset["qr"],
        },
        "visual_elements": visual_meta,
        "theme_name":   theme["_name"],
        "theme_colors": {
            "bg":     theme["bg"],
            "text":   theme["text"],
            "muted":  theme["muted"],
            "accent": theme["accent"],
        },
        "font_profile": font_profile_name,
        "font_sizes_pt": fonts["_size_meta"],
        "fields":       field_annotations,
    }

    ann_filename = f"bizcard_{image_id}.json"
    ann_path = os.path.join(ann_dir, ann_filename)
    with open(ann_path, "w", encoding="utf-8") as f:
        json.dump(annotation, f, ensure_ascii=False, indent=2)

    return img_path, ann_path, annotation


DPI = 300


# =============================================================================
# 데이터셋 일괄 생성
# =============================================================================
def synthesize_dataset(output_dir, samples_per_layout=20, base_seed=42):
    """모든 SELECTED_LAYOUTS에 대해 samples_per_layout 장씩 생성"""
    os.makedirs(output_dir, exist_ok=True)

    summary = {
        "total_images": 0,
        "samples_per_layout": samples_per_layout,
        "layouts": [],
    }

    counter = 0
    for layout_idx, (base_id, vis_id) in enumerate(SELECTED_LAYOUTS):
        composite = get_composite_layout(base_id, vis_id)
        layout_summary = {
            "composite_id":    composite["composite_id"],
            "base_layout_id":  base_id,
            "visual_preset_id": vis_id,
            "orientation":     composite["orientation"],
            "uses_bg_pattern": composite["uses_bg_pattern"],
            "uses_shape":      composite["uses_shape"],
            "uses_logo":       composite["uses_logo"],
            "uses_qr":         composite["uses_qr"],
            "image_ids":       [],
        }

        for sample_i in range(samples_per_layout):
            image_id = f"{counter:05d}"
            seed = base_seed + counter

            try:
                img_path, ann_path, ann = synthesize_card(
                    image_id=image_id,
                    base_layout_id=base_id,
                    visual_preset_id=vis_id,
                    output_dir=output_dir,
                    seed=seed,
                )
                layout_summary["image_ids"].append(image_id)
                counter += 1
            except Exception as e:
                print(f"Failed: layout={base_id}_{vis_id} sample={sample_i} - {e}")
                import traceback
                traceback.print_exc()
                counter += 1
                continue

        summary["layouts"].append(layout_summary)
        summary["total_images"] += len(layout_summary["image_ids"])
        print(f"[{layout_idx+1}/{len(SELECTED_LAYOUTS)}] {composite['composite_id']}: "
              f"{len(layout_summary['image_ids'])}/{samples_per_layout} 생성")

    # 데이터셋 요약 저장
    summary_path = os.path.join(output_dir, "dataset_summary.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print(f"\n총 {summary['total_images']}장 생성 완료 → {output_dir}")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="./bizcard_dataset")
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    synthesize_dataset(args.output, args.samples, args.seed)
