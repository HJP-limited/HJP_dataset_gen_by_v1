"""
시각 요소 렌더링 모듈
- 배경 패턴 (도트/라인/지오메트릭/사선)
- 장식 도형 (구분선, 코너 액센트, 원형 액센트)
- 추상 로고 (모노그램, 기하 도형 조합)
- QR 코드 (qrcode 패키지 없이 Pillow만으로 시각 시뮬레이션)
"""
import random
from PIL import Image, ImageDraw, ImageFont


# =============================================================================
# 색상 유틸리티
# =============================================================================
def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))


def hex_to_rgba(h, alpha=255):
    return hex_to_rgb(h) + (alpha,)


def mix_colors(c1_hex, c2_hex, t=0.5):
    """두 hex 색상을 t 비율로 블렌딩"""
    a = hex_to_rgb(c1_hex)
    b = hex_to_rgb(c2_hex)
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


# =============================================================================
# 배경 패턴
# =============================================================================
def draw_background(img, theme, pattern_type=None):
    """
    배경 채우기. pattern_type이 None이면 단색.
    
    pattern_type 옵션:
      - "solid"  : 단색
      - "dots"   : 도트 패턴
      - "lines_h": 가로 줄무늬
      - "lines_v": 세로 줄무늬
      - "grid"   : 격자
      - "diagonal": 대각선
      - "triangles": 삼각형 타일
    """
    W, H = img.size
    bg = hex_to_rgb(theme["bg"])

    # 베이스 단색 채우기
    img.paste(bg, (0, 0, W, H))

    if pattern_type is None or pattern_type == "solid":
        return

    # 패턴 색은 배경과 5~12% 명도차만 두는 서브틀 톤
    is_dark_bg = (bg[0] + bg[1] + bg[2]) / 3 < 128
    if is_dark_bg:
        # 어두운 배경 → 약간 밝은 패턴색
        pat_color = tuple(min(255, c + 18) for c in bg)
    else:
        # 밝은 배경 → 약간 어두운 패턴색
        pat_color = tuple(max(0, c - 18) for c in bg)

    draw = ImageDraw.Draw(img, "RGBA")
    pat_rgba = pat_color + (255,)

    if pattern_type == "dots":
        spacing = random.randint(28, 48)
        radius = random.randint(2, 4)
        for y in range(spacing // 2, H, spacing):
            for x in range(spacing // 2, W, spacing):
                draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=pat_rgba)

    elif pattern_type == "lines_h":
        spacing = random.randint(18, 36)
        thick = random.randint(1, 2)
        for y in range(0, H, spacing):
            draw.rectangle([0, y, W, y + thick], fill=pat_rgba)

    elif pattern_type == "lines_v":
        spacing = random.randint(18, 36)
        thick = random.randint(1, 2)
        for x in range(0, W, spacing):
            draw.rectangle([x, 0, x + thick, H], fill=pat_rgba)

    elif pattern_type == "grid":
        spacing = random.randint(30, 50)
        thick = 1
        for y in range(0, H, spacing):
            draw.rectangle([0, y, W, y + thick], fill=pat_rgba)
        for x in range(0, W, spacing):
            draw.rectangle([x, 0, x + thick, H], fill=pat_rgba)

    elif pattern_type == "diagonal":
        spacing = random.randint(24, 40)
        thick = random.randint(1, 2)
        # 사선 라인을 PIL 라인으로 그리기
        for k in range(-H, W, spacing):
            draw.line([(k, 0), (k + H, H)], fill=pat_rgba, width=thick)

    elif pattern_type == "triangles":
        size = random.randint(40, 70)
        for y in range(-size, H + size, size):
            for x in range(-size, W + size, size * 2):
                offset = (size if (y // size) % 2 else 0)
                # 작은 삼각형 윤곽
                pts = [
                    (x + offset, y),
                    (x + offset + size, y),
                    (x + offset + size // 2, y + size),
                ]
                draw.polygon(pts, outline=pat_rgba, width=1)


# =============================================================================
# 장식 도형 (구분선, 액센트)
# =============================================================================
def draw_decorative_shapes(img, theme, region, kinds=None):
    """
    명함 장식 도형 그리기.
    region: 도형을 그릴 수 있는 영역 (x1, y1, x2, y2)
    kinds: 그릴 도형 종류 리스트. None이면 자동 선택.
    
    Returns: 사용된 도형 종류 리스트 (어노테이션용)
    """
    if kinds is None:
        kinds = random.sample(["divider_line", "corner_accent", "circle_accent"],
                              k=random.randint(1, 2))

    draw = ImageDraw.Draw(img, "RGBA")
    accent = hex_to_rgba(theme["accent"], 255)
    muted = hex_to_rgba(theme["muted"], 200)

    rx1, ry1, rx2, ry2 = region
    used = []

    if "divider_line" in kinds:
        # 가로 구분선 (영역 가운데 즈음)
        y = ry1 + (ry2 - ry1) * random.choice([0.4, 0.5, 0.6])
        x1 = rx1 + 8
        x2 = rx1 + 60
        draw.rectangle([x1, y - 1, x2, y + 1], fill=accent)
        used.append("divider_line")

    if "corner_accent" in kinds:
        # 우상단 코너에 작은 사각/원
        size = random.randint(24, 50)
        cx, cy = rx2 - size - 8, ry1 + 8
        if random.random() < 0.5:
            draw.rectangle([cx, cy, cx + size, cy + size], fill=accent)
        else:
            draw.ellipse([cx, cy, cx + size, cy + size], fill=accent)
        used.append("corner_accent")

    if "circle_accent" in kinds:
        # 우하단 또는 좌하단의 큰 원호 (반투명)
        r = random.randint(80, 140)
        if random.random() < 0.5:
            cx, cy = rx2 + 30, ry2 + 20
        else:
            cx, cy = rx1 - 30, ry2 + 20
        # 반투명 큰 원
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=hex_to_rgba(theme["accent"], 60))
        used.append("circle_accent")

    return used


# =============================================================================
# 추상 로고
# =============================================================================
def make_abstract_logo_image(size, theme, company_initial=""):
    """
    추상 로고 이미지 (RGBA) 생성. 실존 브랜드 회피를 위해
    기하 도형 조합 또는 모노그램 형태로만 생성.
    
    size: 로고 한 변 픽셀 (정사각)
    theme: 색상 테마 dict
    company_initial: 모노그램에 들어갈 1글자 (없으면 도형형)
    
    Returns: (logo_image, logo_kind_str)
    """
    W = H = size
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")

    accent = hex_to_rgba(theme["accent"], 255)
    text_col = hex_to_rgba(theme["text"], 255)

    kind = random.choice([
        "circle_filled_initial",   # 채워진 원 + 글자
        "rounded_square_initial",  # 라운드 사각 + 글자
        "geometric_overlap",       # 두 도형 겹침
        "ring_with_dot",           # 링 + 중앙 점
        "triangle_stack",          # 삼각형 2개 스택
    ])

    if kind == "circle_filled_initial" and company_initial:
        draw.ellipse([4, 4, W - 4, H - 4], fill=accent)
        # 가운데 글자는 호출자가 별도 폰트로 그려야 함
        return img, kind

    if kind == "rounded_square_initial" and company_initial:
        # PIL의 둥근 사각형
        try:
            draw.rounded_rectangle([4, 4, W - 4, H - 4], radius=W // 6, fill=accent)
        except AttributeError:
            draw.rectangle([4, 4, W - 4, H - 4], fill=accent)
        return img, kind

    if kind == "geometric_overlap":
        # 원 + 사각이 겹치는 형태 (반투명)
        draw.ellipse([2, 2, W - W // 3, H - H // 3], fill=hex_to_rgba(theme["accent"], 200))
        draw.rectangle([W // 3, H // 3, W - 2, H - 2], fill=hex_to_rgba(theme["text"], 180))
        return img, kind

    if kind == "ring_with_dot":
        # 두꺼운 원 윤곽 + 중앙 점
        draw.ellipse([4, 4, W - 4, H - 4], outline=accent, width=max(3, W // 12))
        cr = W // 6
        cx, cy = W // 2, H // 2
        draw.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=accent)
        return img, kind

    if kind == "triangle_stack":
        # 삼각형 2개를 위아래로 쌓기 (반투명 겹침)
        pts1 = [(W // 2, 6), (W - 6, H - 6), (6, H - 6)]
        draw.polygon(pts1, fill=hex_to_rgba(theme["accent"], 220))
        pts2 = [(W // 2, H - 6), (6, 6), (W - 6, 6)]
        draw.polygon(pts2, fill=hex_to_rgba(theme["text"], 100))
        return img, kind

    # fallback: 단순 사각형
    draw.rectangle([4, 4, W - 4, H - 4], fill=accent)
    return img, "rectangle"


def paste_logo_with_initial(card_img, logo_img, kind, position, size,
                             theme, font_path, initial=""):
    """
    로고를 card에 붙이고, 필요 시 가운데에 글자(이니셜)를 그린다.
    position: (x, y) - 로고 좌상단 좌표
    """
    card_img.alpha_composite(logo_img, dest=position)

    if initial and kind in ("circle_filled_initial", "rounded_square_initial"):
        x, y = position
        # 로고 위에 글자 그리기 - 컨테이너가 채워진 색이므로 흰색 또는 배경색을 사용
        # 배경색이 어두우면 흰글자, 밝으면 어두운 글자
        bg = hex_to_rgb(theme["accent"])
        is_dark = (bg[0] + bg[1] + bg[2]) / 3 < 128
        glyph_color = (255, 255, 255) if is_dark else (20, 20, 20)

        font_size = int(size * 0.55)
        try:
            font = ImageFont.truetype(font_path, font_size)
        except Exception:
            font = ImageFont.load_default()

        draw = ImageDraw.Draw(card_img)
        # 텍스트 중앙 정렬
        bbox = draw.textbbox((0, 0), initial, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        cx = x + size // 2 - tw // 2 - bbox[0]
        cy = y + size // 2 - th // 2 - bbox[1]
        draw.text((cx, cy), initial, fill=glyph_color, font=font)


# =============================================================================
# QR 코드 (qrcode 패키지 없이 Pillow로 시각 시뮬레이션)
# =============================================================================
def draw_qr_code_visual(img, x, y, size, theme, seed=None):
    """
    실제 인코딩되는 QR이 아니라, OCR/명함 인식 모델 학습용으로
    시각적으로 QR과 유사한 모듈 격자를 그린다.
    
    구조: finder pattern(7x7) 3개 + 가운데 랜덤 모듈 격자.
    
    Returns: (실제 그려진 영역 bbox, 인코딩 시뮬레이션 데이터)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    # QR 모듈 그리드 - 25x25 (Version 2 정도)
    grid_size = 25
    module_size = max(2, size // grid_size)
    actual_size = module_size * grid_size

    # quiet zone (배경 흰색 패딩) 4모듈
    quiet = module_size * 3
    full_size = actual_size + quiet * 2

    # 배경 (밝은 색)
    qr_bg_color = hex_to_rgb(theme["bg"])
    qr_fg_color = hex_to_rgb(theme["text"])
    # 배경이 너무 어두우면 흰색/검정으로
    if (qr_bg_color[0] + qr_bg_color[1] + qr_bg_color[2]) / 3 < 100:
        qr_bg_color = (255, 255, 255)
        qr_fg_color = (0, 0, 0)

    draw = ImageDraw.Draw(img)
    # quiet zone 포함한 흰 배경
    draw.rectangle([x, y, x + full_size, y + full_size], fill=qr_bg_color)

    grid_x = x + quiet
    grid_y = y + quiet

    # 1) finder patterns (좌상, 우상, 좌하)
    def draw_finder(gx, gy):
        # 7x7 outer
        for r in range(7):
            for c in range(7):
                if r == 0 or r == 6 or c == 0 or c == 6:
                    px = grid_x + (gx + c) * module_size
                    py = grid_y + (gy + r) * module_size
                    draw.rectangle([px, py, px + module_size, py + module_size], fill=qr_fg_color)
        # 3x3 inner
        for r in range(2, 5):
            for c in range(2, 5):
                px = grid_x + (gx + c) * module_size
                py = grid_y + (gy + r) * module_size
                draw.rectangle([px, py, px + module_size, py + module_size], fill=qr_fg_color)

    draw_finder(0, 0)
    draw_finder(grid_size - 7, 0)
    draw_finder(0, grid_size - 7)

    # 2) 데이터 영역 - 랜덤 모듈로 채움
    def in_finder(r, c):
        # 좌상
        if r < 8 and c < 8:
            return True
        # 우상
        if r < 8 and c >= grid_size - 8:
            return True
        # 좌하
        if r >= grid_size - 8 and c < 8:
            return True
        return False

    # timing pattern (6번째 행/열)
    for c in range(8, grid_size - 8):
        if c % 2 == 0:
            px = grid_x + c * module_size
            py = grid_y + 6 * module_size
            draw.rectangle([px, py, px + module_size, py + module_size], fill=qr_fg_color)
    for r in range(8, grid_size - 8):
        if r % 2 == 0:
            px = grid_x + 6 * module_size
            py = grid_y + r * module_size
            draw.rectangle([px, py, px + module_size, py + module_size], fill=qr_fg_color)

    # 데이터 모듈 - 랜덤 (대략 50% 채움)
    for r in range(grid_size):
        for c in range(grid_size):
            if in_finder(r, c):
                continue
            if r == 6 or c == 6:  # timing pattern 자리
                continue
            if rng.random() < 0.5:
                px = grid_x + c * module_size
                py = grid_y + r * module_size
                draw.rectangle([px, py, px + module_size, py + module_size], fill=qr_fg_color)

    return (x, y, x + full_size, y + full_size), full_size
