"""
텍스트 렌더링 + bbox 계산 유틸리티
- Pillow의 textbbox를 사용해 정확한 픽셀 단위 bbox 산출
- 명함 어노테이션 형식에 맞게 (x1, y1, x2, y2) xyxy 포맷 반환
"""
from PIL import ImageDraw, ImageFont


def get_font(font_path, size_px, index=1):
    """
    Noto Sans/Serif CJK는 .ttc 콜렉션. 한국어 face는 보통 index=1.
    실패 시 index=0으로 fallback.
    """
    try:
        return ImageFont.truetype(font_path, size_px, index=index)
    except Exception:
        try:
            return ImageFont.truetype(font_path, size_px, index=0)
        except Exception:
            return ImageFont.truetype(font_path, size_px)


def measure_text(draw, text, font):
    """
    textbbox 기반으로 텍스트의 (width, height) 측정.
    bbox의 x_offset, y_offset도 함께 반환 (렌더 시 보정용).
    """
    bbox = draw.textbbox((0, 0), text, font=font)
    return {
        "width":  bbox[2] - bbox[0],
        "height": bbox[3] - bbox[1],
        "x_off":  bbox[0],
        "y_off":  bbox[1],
    }


def draw_text_with_bbox(img, draw, text, x, y, font, color, anchor="lt"):
    """
    텍스트를 그리고 실제 bbox(xyxy) 반환.
    anchor:
      - "lt": (x, y)가 텍스트의 좌상단 (기본)
      - "lm": (x, y)가 텍스트의 왼쪽 중앙
      - "mt": (x, y)가 텍스트의 상단 중앙
      - "mm": (x, y)가 텍스트의 정중앙
      - "rt": (x, y)가 텍스트의 우상단
    
    Returns: (rendered_text, bbox_xyxy)
    """
    bbox_offset = draw.textbbox((0, 0), text, font=font)
    text_w = bbox_offset[2] - bbox_offset[0]
    text_h = bbox_offset[3] - bbox_offset[1]
    x_off = bbox_offset[0]
    y_off = bbox_offset[1]

    # 앵커별로 실제 그릴 좌상단 좌표 계산
    if anchor == "lt":
        draw_x, draw_y = x, y
    elif anchor == "lm":
        draw_x, draw_y = x, y - text_h // 2
    elif anchor == "mt":
        draw_x, draw_y = x - text_w // 2, y
    elif anchor == "mm":
        draw_x, draw_y = x - text_w // 2, y - text_h // 2
    elif anchor == "rt":
        draw_x, draw_y = x - text_w, y
    else:
        draw_x, draw_y = x, y

    # PIL의 text() 호출 시 좌표는 textbbox 오프셋을 보정해야
    # 실제 글자 박스의 좌상단이 그 위치에 오게 됨.
    draw.text((draw_x - x_off, draw_y - y_off), text, fill=color, font=font)

    bbox_xyxy = [draw_x, draw_y, draw_x + text_w, draw_y + text_h]
    return text, bbox_xyxy
