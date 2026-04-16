from __future__ import annotations

import argparse
import json
import random
import sys
import traceback
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from qa_checks import validate_output, write_validation_report


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def text_width(font: ImageFont.FreeTypeFont, text: str) -> int:
    box = font.getbbox(text)
    return box[2] - box[0]


def wrap_text(text: str, font: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    if not text or text_width(font, text) <= max_width:
        return [text]
    words = text.split(" ")
    lines: list[str] = []
    current = ""
    for word in words:
        trial = word if not current else f"{current} {word}"
        if text_width(font, trial) <= max_width:
            current = trial
            continue
        if current:
            lines.append(current)
        if text_width(font, word) <= max_width:
            current = word
            continue
        char_line = ""
        for char in word:
            trial = char if not char_line else char_line + char
            if text_width(font, trial) <= max_width:
                char_line = trial
            else:
                lines.append(char_line)
                char_line = char
        current = char_line
    if current:
        lines.append(current)
    return lines


def union_boxes(boxes: list[tuple[int, int, int, int]]) -> tuple[int, int, int, int]:
    return (
        min(box[0] for box in boxes),
        min(box[1] for box in boxes),
        max(box[2] for box in boxes),
        max(box[3] for box in boxes),
    )


def clamp_box(box: tuple[int, int, int, int], width: int, height: int) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = box
    return (
        max(0, min(width, x1)),
        max(0, min(height, y1)),
        max(0, min(width, x2)),
        max(0, min(height, y2)),
    )


class FontManager:
    def __init__(self, fonts_config: dict):
        self.profiles = fonts_config["profiles"]
        self.paths = {
            key: [Path(item) for item in values if Path(item).exists()]
            for key, values in fonts_config["candidates"].items()
        }
        if not self.paths["regular"]:
            raise FileNotFoundError("No regular font candidates available")
        if not self.paths["bold"]:
            self.paths["bold"] = self.paths["regular"]
        self.cache: dict[tuple[str, int, str], ImageFont.FreeTypeFont] = {}

    def choose_profile(self) -> dict:
        return random.choice(self.profiles)

    def get(self, style: str, size: int, profile_name: str) -> ImageFont.FreeTypeFont:
        key = (style, size, profile_name)
        if key not in self.cache:
            self.cache[key] = ImageFont.truetype(str(self.paths[style][0]), size)
        return self.cache[key]


class DataFactory:
    def __init__(self, config: dict, company_domain_map: dict):
        self.config = config
        self.company_domain_map = company_domain_map

    def gen_name(self) -> str:
        return random.choice(self.config["last_names"]) + random.choice(self.config["first_names"])

    def gen_company(self) -> str:
        return random.choice(self.config["companies"])

    def gen_position(self) -> str:
        names = [item["name"] for item in self.config["positions"]]
        weights = [item["weight"] for item in self.config["positions"]]
        return random.choices(names, weights=weights, k=1)[0]

    def gen_department(self) -> str:
        return random.choice(self.config["departments"])

    def gen_phone(self, mobile: bool) -> str:
        if mobile:
            prefix = random.choice(self.config["mobile_prefixes"])
            middle = f"{random.randint(1000, 9999)}"
        else:
            prefix = random.choice(self.config["phone_prefixes"])
            middle = f"{random.randint(100, 9999) if prefix == '02' else random.randint(100, 999)}"
        last = f"{random.randint(1000, 9999)}"
        separator = random.choice(["-", "."])
        return separator.join([prefix, middle, last])

    def gen_address(self) -> str:
        city = random.choice(self.config["cities"])
        districts = self.config["districts"].get(city, [""])
        district = random.choice(districts) if districts else ""
        road = random.choice(self.config["roads"])
        number = random.randint(1, 250)
        detail = random.choice(
            [
                f"{number}",
                f"{number}-{random.randint(1, 20)}",
                f"{number} {random.randint(2, 18)}층",
                f"{number} {random.choice(['A', 'B', 'C'])}동 {random.randint(101, 1509)}호",
            ]
        )
        return " ".join(part for part in [city, district, road, detail] if part)

    def gen_email(self, name: str, company: str) -> str:
        mapped = self.company_domain_map.get(company)
        domain = mapped if mapped and random.random() < 0.72 else random.choice(self.config["email_domains"])
        stem = "".join(ch for ch in name.lower() if ch.isascii() and ch.isalnum())
        if not stem:
            stem = "user" + str(random.randint(10, 999))
        return random.choice(
            [
                f"{stem}@{domain}",
                f"{stem}{random.randint(1, 99)}@{domain}",
                f"{stem}.{random.randint(10, 99)}@{domain}",
                f"user{random.randint(100, 999)}@{domain}",
            ]
        )

    def gen_website(self, company: str) -> str:
        prefix = random.choice(self.config["website_prefixes"])
        domain = self.company_domain_map.get(company, random.choice(self.config["website_domains"]))
        return f"{prefix}.{domain}" if prefix else domain

    def gen_postcode(self) -> str:
        return f"{random.randint(10000, 69999)}"

    def build(self) -> dict[str, str]:
        probs = self.config["field_probabilities"]
        name = self.gen_name()
        company = self.gen_company()
        data = {
            "name": name,
            "company": company,
            "position": self.gen_position(),
            "department": self.gen_department() if random.random() < probs["department"] else "",
            "phone": self.gen_phone(False) if random.random() < probs["phone"] else "",
            "mobile": self.gen_phone(True) if random.random() < probs["mobile"] else "",
            "fax": self.gen_phone(False) if random.random() < probs["fax"] else "",
            "email": self.gen_email(name, company) if random.random() < probs["email"] else "",
            "address": self.gen_address() if random.random() < probs["address"] else "",
            "website": self.gen_website(company) if random.random() < probs["website"] else "",
            "postcode": self.gen_postcode() if random.random() < probs["postcode"] else "",
        }
        while sum(1 for value in data.values() if value) < self.config["min_present_fields"]:
            for field_name in ["department", "phone", "mobile", "email", "address", "website", "postcode"]:
                if data[field_name]:
                    continue
                if field_name == "department":
                    data[field_name] = self.gen_department()
                elif field_name == "phone":
                    data[field_name] = self.gen_phone(False)
                elif field_name == "mobile":
                    data[field_name] = self.gen_phone(True)
                elif field_name == "email":
                    data[field_name] = self.gen_email(name, company)
                elif field_name == "address":
                    data[field_name] = self.gen_address()
                elif field_name == "website":
                    data[field_name] = self.gen_website(company)
                elif field_name == "postcode":
                    data[field_name] = self.gen_postcode()
                if sum(1 for value in data.values() if value) >= self.config["min_present_fields"]:
                    break
        return data


class AnnotationBuilder:
    def __init__(self, image_id: str, classes: dict[str, int]):
        self.image_id = image_id
        self.classes = classes
        self.fields: list[dict[str, Any]] = []
        self.reading_order = 1

    def add(
        self,
        field_class: str,
        rendered_text: str,
        canonical_text: str,
        box: tuple[int, int, int, int],
        line_boxes: list[tuple[int, int, int, int]],
    ) -> None:
        field_id = f"{self.image_id}_{len(self.fields) + 1:03d}"
        self.fields.append(
            {
                "field_id": field_id,
                "field_class": field_class,
                "field_class_id": self.classes[field_class],
                "rendered_text": rendered_text,
                "canonical_text": canonical_text,
                "bbox_xyxy": list(box),
                "bbox_size": {"width": box[2] - box[0], "height": box[3] - box[1]},
                "reading_order": self.reading_order,
                "line_ids": [f"{field_id}_l{index + 1:02d}" for index in range(len(line_boxes))],
                "line_boxes": [list(item) for item in line_boxes],
                "block_id": f"{field_id}_b01",
                "language": "ko",
            }
        )
        self.reading_order += 1


@dataclass
class RenderContext:
    image: Image.Image
    draw: ImageDraw.ImageDraw
    builder: AnnotationBuilder
    font_manager: FontManager
    font_profile: dict
    theme: dict
    prefixes: dict[str, list[str]]

    @property
    def width(self) -> int:
        return self.image.size[0]

    @property
    def height(self) -> int:
        return self.image.size[1]

    def font(self, role: str, size: int) -> ImageFont.FreeTypeFont:
        style = self.font_profile[role]
        return self.font_manager.get(style, size, self.font_profile["name"])

    def render_text_block(
        self,
        field_class: str,
        rendered_text: str,
        canonical_text: str,
        x: int,
        y: int,
        font: ImageFont.FreeTypeFont,
        color: tuple[int, int, int],
        max_width: int,
    ) -> int:
        lines = wrap_text(rendered_text, font, max_width)
        line_boxes: list[tuple[int, int, int, int]] = []
        current_y = y
        for line in lines:
            box = tuple(map(int, self.draw.textbbox((x, current_y), line, font=font)))
            self.draw.text((x, current_y), line, font=font, fill=color)
            line_boxes.append(box)
            current_y = box[3] + 4
        box = clamp_box(union_boxes(line_boxes), self.width, self.height)
        self.builder.add(field_class, rendered_text, canonical_text, box, line_boxes)
        return current_y

    def render_centered(
        self,
        field_class: str,
        rendered_text: str,
        canonical_text: str,
        center_x: int,
        y: int,
        font: ImageFont.FreeTypeFont,
        color: tuple[int, int, int],
        max_width: int,
    ) -> int:
        lines = wrap_text(rendered_text, font, max_width)
        line_boxes: list[tuple[int, int, int, int]] = []
        current_y = y
        for line in lines:
            line_w = text_width(font, line)
            x = center_x - line_w // 2
            box = tuple(map(int, self.draw.textbbox((x, current_y), line, font=font)))
            self.draw.text((x, current_y), line, font=font, fill=color)
            line_boxes.append(box)
            current_y = box[3] + 4
        box = clamp_box(union_boxes(line_boxes), self.width, self.height)
        self.builder.add(field_class, rendered_text, canonical_text, box, line_boxes)
        return current_y

    def render_contact(
        self,
        field_class: str,
        canonical_text: str,
        x: int,
        y: int,
        font: ImageFont.FreeTypeFont,
        color: tuple[int, int, int],
        max_width: int,
    ) -> int:
        prefix = random.choice(self.prefixes.get(field_class, [""]))
        rendered_text = f"{prefix} {canonical_text}".strip()
        return self.render_text_block(field_class, rendered_text, canonical_text, x, y, font, color, max_width)

    def separator(self, x1: int, y1: int, x2: int, y2: int, width: int = 2) -> None:
        self.draw.line([(x1, y1), (x2, y2)], fill=tuple(self.theme["accent"]), width=width)


def choose_card_spec(config: dict) -> dict:
    weights = [item["weight"] for item in config["card_specs"]]
    return random.choices(config["card_specs"], weights=weights, k=1)[0]


def make_background(size: tuple[int, int], theme: dict) -> Image.Image:
    image = Image.new("RGB", size, tuple(theme["bg"]))
    draw = ImageDraw.Draw(image)
    if random.random() < 0.35:
        horizontal = random.random() < 0.5
        steps = size[0] if horizontal else size[1]
        bg = theme["bg"]
        accent = theme["accent"]
        for i in range(steps):
            ratio = i / max(1, steps - 1)
            color = tuple(int(bg[c] * (1 - ratio) + accent[c] * ratio * 0.12) for c in range(3))
            if horizontal:
                draw.line([(i, 0), (i, size[1])], fill=color)
            else:
                draw.line([(0, i), (size[0], i)], fill=color)
    return image


def layout_horizontal_left(ctx: RenderContext, data: dict[str, str]) -> None:
    margin_x = int(ctx.width * 0.06)
    y = int(ctx.height * 0.10)
    company_font = ctx.font("title", max(24, int(ctx.height * 0.045)))
    name_font = ctx.font("title", max(42, int(ctx.height * 0.085)))
    body_font = ctx.font("body", max(18, int(ctx.height * 0.035)))
    small_font = ctx.font("label", max(16, int(ctx.height * 0.028)))
    y = ctx.render_text_block("company", data["company"], data["company"], margin_x, y, company_font, tuple(ctx.theme["accent"]), int(ctx.width * 0.54))
    y += 8
    ctx.separator(margin_x, y, ctx.width - margin_x, y, 2)
    y += 16
    y = ctx.render_text_block("name", data["name"], data["name"], margin_x, y, name_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.60))
    y += 6
    if data["department"]:
        y = ctx.render_text_block("department", data["department"], data["department"], margin_x, y, body_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.60))
        y += 2
    y = ctx.render_text_block("position", data["position"], data["position"], margin_x, y, body_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.60))
    y += 16
    for field_name in ["phone", "mobile", "fax", "email", "address", "website", "postcode"]:
        if data[field_name]:
            y = ctx.render_contact(field_name, data[field_name], margin_x, y, small_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.82))
            y += 6


def layout_horizontal_right(ctx: RenderContext, data: dict[str, str]) -> None:
    margin_x = int(ctx.width * 0.06)
    y_left = int(ctx.height * 0.10)
    info_font = ctx.font("body", max(17, int(ctx.height * 0.031)))
    title_font = ctx.font("title", max(22, int(ctx.height * 0.042)))
    name_font = ctx.font("title", max(38, int(ctx.height * 0.076)))
    for field_name in ["phone", "mobile", "email", "address", "website", "postcode"]:
        if data[field_name]:
            y_left = ctx.render_contact(field_name, data[field_name], margin_x, y_left, info_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.36))
            y_left += 8
    divider_x = int(ctx.width * 0.47)
    ctx.separator(divider_x, int(ctx.height * 0.10), divider_x, int(ctx.height * 0.90), 2)
    x_right = int(ctx.width * 0.53)
    y_right = int(ctx.height * 0.10)
    y_right = ctx.render_text_block("company", data["company"], data["company"], x_right, y_right, title_font, tuple(ctx.theme["accent"]), int(ctx.width * 0.36))
    y_right += 14
    y_right = ctx.render_text_block("name", data["name"], data["name"], x_right, y_right, name_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.36))
    y_right += 8
    if data["department"]:
        y_right = ctx.render_text_block("department", data["department"], data["department"], x_right, y_right, info_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.36))
        y_right += 2
    ctx.render_text_block("position", data["position"], data["position"], x_right, y_right, info_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.36))


def layout_split_horizontal(ctx: RenderContext, data: dict[str, str]) -> None:
    banner_h = int(ctx.height * 0.42)
    ctx.draw.rectangle([(0, 0), (ctx.width, banner_h)], fill=tuple(ctx.theme["accent"]))
    margin_x = int(ctx.width * 0.06)
    banner_text = (255, 255, 255) if sum(ctx.theme["accent"]) < 380 else (20, 20, 20)
    y = int(ctx.height * 0.08)
    name_font = ctx.font("title", max(42, int(ctx.height * 0.082)))
    body_font = ctx.font("body", max(20, int(ctx.height * 0.036)))
    small_font = ctx.font("label", max(16, int(ctx.height * 0.028)))
    y = ctx.render_text_block("name", data["name"], data["name"], margin_x, y, name_font, banner_text, int(ctx.width * 0.80))
    if data["department"]:
        y = ctx.render_text_block("department", data["department"], data["department"], margin_x, y + 2, body_font, banner_text, int(ctx.width * 0.80))
    ctx.render_text_block("position", data["position"], data["position"], margin_x, y + 4, body_font, banner_text, int(ctx.width * 0.80))
    y = banner_h + int(ctx.height * 0.08)
    y = ctx.render_text_block("company", data["company"], data["company"], margin_x, y, body_font, tuple(ctx.theme["accent"]), int(ctx.width * 0.80))
    y += 12
    for field_name in ["phone", "mobile", "email", "address", "website", "postcode"]:
        if data[field_name]:
            y = ctx.render_contact(field_name, data[field_name], margin_x, y, small_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.86))
            y += 6


def layout_minimalist(ctx: RenderContext, data: dict[str, str]) -> None:
    margin_x = int(ctx.width * 0.08)
    y = int(ctx.height * 0.12)
    name_font = ctx.font("title", max(48, int(ctx.height * 0.092)))
    body_font = ctx.font("body", max(20, int(ctx.height * 0.036)))
    small_font = ctx.font("label", max(16, int(ctx.height * 0.028)))
    y = ctx.render_text_block("name", data["name"], data["name"], margin_x, y, name_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.84))
    y += 6
    y = ctx.render_text_block("company", data["company"], data["company"], margin_x, y, body_font, tuple(ctx.theme["accent"]), int(ctx.width * 0.84))
    y += 4
    if data["department"]:
        y = ctx.render_text_block("department", data["department"], data["department"], margin_x, y, small_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.84))
        y += 2
    y = ctx.render_text_block("position", data["position"], data["position"], margin_x, y, body_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.84))
    y += 14
    ctx.separator(margin_x, y, ctx.width - margin_x, y, 2)
    y += 16
    for field_name in ["email", "mobile", "phone", "website", "address", "postcode"]:
        if data[field_name]:
            y = ctx.render_contact(field_name, data[field_name], margin_x, y, small_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.84))
            y += 6


def layout_vertical_center(ctx: RenderContext, data: dict[str, str]) -> None:
    center_x = ctx.width // 2
    y = int(ctx.height * 0.10)
    company_font = ctx.font("title", max(24, int(ctx.width * 0.04)))
    name_font = ctx.font("title", max(40, int(ctx.width * 0.075)))
    body_font = ctx.font("body", max(18, int(ctx.width * 0.032)))
    small_font = ctx.font("label", max(16, int(ctx.width * 0.027)))
    y = ctx.render_centered("company", data["company"], data["company"], center_x, y, company_font, tuple(ctx.theme["accent"]), int(ctx.width * 0.78))
    y += 10
    y = ctx.render_centered("name", data["name"], data["name"], center_x, y, name_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.78))
    y += 6
    if data["department"]:
        y = ctx.render_centered("department", data["department"], data["department"], center_x, y, body_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.78))
        y += 2
    y = ctx.render_centered("position", data["position"], data["position"], center_x, y, body_font, tuple(ctx.theme["text_sub"]), int(ctx.width * 0.78))
    y += 14
    margin_x = int(ctx.width * 0.10)
    ctx.separator(margin_x, y, ctx.width - margin_x, y, 2)
    y += 18
    for field_name in ["phone", "mobile", "email", "address", "website", "postcode", "fax"]:
        if data[field_name]:
            y = ctx.render_contact(field_name, data[field_name], margin_x, y, small_font, tuple(ctx.theme["text_main"]), int(ctx.width * 0.80))
            y += 6


HORIZONTAL_LAYOUTS = {
    "horizontal_left": layout_horizontal_left,
    "horizontal_right": layout_horizontal_right,
    "split_horizontal": layout_split_horizontal,
    "minimalist": layout_minimalist,
}

VERTICAL_LAYOUTS = {
    "vertical_center": layout_vertical_center,
}


def build_output_dirs(root: Path) -> dict[str, Path]:
    paths = {
        "root": root,
        "images": root / "images",
        "labels_yolo": root / "labels_yolo",
        "coco": root / "coco",
        "master": root / "master_annotations",
        "det": root / "det",
        "rec": root / "rec",
        "rec_images": root / "rec" / "images",
        "kie": root / "kie",
        "previews": root / "previews",
        "splits": root / "splits",
        "logs": root / "logs",
    }
    for path in paths.values():
        path.mkdir(parents=True, exist_ok=True)
    return paths


def build_record(
    image_id: str,
    image_filename: str,
    image_size: tuple[int, int],
    orientation: str,
    spec: dict,
    layout_name: str,
    theme: dict,
    font_profile: dict,
    fields: list[dict],
) -> dict:
    return {
        "image_id": image_id,
        "image_path": f"images/{image_filename}",
        "image_size": {"width": image_size[0], "height": image_size[1]},
        "card_orientation": orientation,
        "card_spec_mm": {"width": spec["width_mm"], "height": spec["height_mm"]},
        "layout_type": layout_name,
        "theme_name": theme["name"],
        "font_profile": font_profile["name"],
        "fields": fields,
    }


def bbox_to_yolo(box: list[int], width: int, height: int) -> tuple[float, float, float, float]:
    x1, y1, x2, y2 = box
    return ((x1 + x2) / 2 / width, (y1 + y2) / 2 / height, (x2 - x1) / width, (y2 - y1) / height)


def save_yolo(path: Path, record: dict, classes: dict[str, int]) -> None:
    lines = []
    width = record["image_size"]["width"]
    height = record["image_size"]["height"]
    for field in record["fields"]:
        x1, y1, x2, y2 = field["bbox_xyxy"]
        if x2 <= x1 or y2 <= y1:
            continue
        cx, cy, bw, bh = bbox_to_yolo(field["bbox_xyxy"], width, height)
        lines.append(f"{classes[field['field_class']]} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    path.write_text("\n".join(lines), encoding="utf-8")


def draw_preview(image: Image.Image, record: dict, path: Path) -> None:
    preview = image.copy()
    draw = ImageDraw.Draw(preview)
    colors = {
        "name": (255, 80, 80),
        "company": (80, 160, 255),
        "position": (80, 220, 80),
        "department": (220, 180, 60),
        "phone": (200, 110, 255),
        "mobile": (255, 160, 30),
        "fax": (100, 200, 200),
        "email": (255, 100, 170),
        "address": (100, 255, 150),
        "website": (200, 200, 100),
        "postcode": (180, 180, 255),
    }
    font = ImageFont.load_default()
    for field in record["fields"]:
        x1, y1, x2, y2 = field["bbox_xyxy"]
        color = colors.get(field["field_class"], (200, 200, 200))
        draw.rectangle([(x1, y1), (x2, y2)], outline=color, width=2)
        draw.text((x1, max(0, y1 - 12)), field["field_class"], fill=color, font=font)
    preview.save(path, "JPEG", quality=90)


def create_splits(ids: list[str]) -> dict[str, list[str]]:
    items = ids[:]
    random.shuffle(items)
    n_train = int(len(items) * 0.8)
    n_val = int(len(items) * 0.1)
    return {
        "train": items[:n_train],
        "val": items[n_train:n_train + n_val],
        "test": items[n_train + n_val:],
    }


def generate_card(
    image_id: str,
    config: dict,
    themes: list[dict],
    font_manager: FontManager,
    data_factory: DataFactory,
) -> tuple[Image.Image, dict]:
    spec = choose_card_spec(config)
    vertical = random.random() < config["vertical_ratio"]
    orientation = "vertical" if vertical else "horizontal"
    size = (spec["height_px"], spec["width_px"]) if vertical else (spec["width_px"], spec["height_px"])
    theme = random.choice(themes)
    image = make_background(size, theme)
    draw = ImageDraw.Draw(image)
    font_profile = font_manager.choose_profile()
    classes = {name: idx for idx, name in enumerate(config["classes"])}
    builder = AnnotationBuilder(image_id, classes)
    ctx = RenderContext(image=image, draw=draw, builder=builder, font_manager=font_manager, font_profile=font_profile, theme=theme, prefixes=config["contact_prefixes"])
    data = data_factory.build()
    if vertical:
        layout_name, layout_fn = random.choice(list(VERTICAL_LAYOUTS.items()))
    else:
        layout_name, layout_fn = random.choice(list(HORIZONTAL_LAYOUTS.items()))
    layout_fn(ctx, data)
    image_filename = f"bizcard_{image_id}.jpg"
    record = build_record(image_id, image_filename, image.size, orientation, spec, layout_name, theme, font_profile, builder.fields)
    return image, record


def export_all(
    output_paths: dict[str, Path],
    config: dict,
    records: dict[str, dict],
    rec_entries: list[dict[str, str]],
    splits: dict[str, list[str]],
) -> None:
    classes = {name: idx for idx, name in enumerate(config["classes"])}
    split_of_id = {image_id: split for split, ids in splits.items() for image_id in ids}
    for split, ids in splits.items():
        lines = [f"images/bizcard_{image_id}.jpg" for image_id in ids]
        (output_paths["splits"] / f"{split}.txt").write_text("\n".join(lines), encoding="utf-8")
    (output_paths["splits"] / "successful_ids.json").write_text(json.dumps(list(records.keys()), ensure_ascii=False, indent=2), encoding="utf-8")

    coco = {
        "info": {
            "description": "Korean Business Card Synthetic Dataset Rebuild V2",
            "version": "2.0",
            "year": datetime.now().year,
        },
        "categories": [{"id": idx, "name": name, "supercategory": "bizcard"} for name, idx in classes.items()],
        "images": [],
        "annotations": [],
    }
    ann_id = 0
    det_lines = {"train": [], "val": [], "test": []}
    rec_lines = {"train": [], "val": [], "test": []}

    for image_id, record in records.items():
        coco["images"].append({"id": int(image_id), "file_name": f"bizcard_{image_id}.jpg", "width": record["image_size"]["width"], "height": record["image_size"]["height"]})
        for field in record["fields"]:
            x1, y1, x2, y2 = field["bbox_xyxy"]
            coco["annotations"].append({"id": ann_id, "image_id": int(image_id), "category_id": field["field_class_id"], "bbox": [x1, y1, x2 - x1, y2 - y1], "area": (x2 - x1) * (y2 - y1), "iscrowd": 0, "text": field["rendered_text"], "canonical_text": field["canonical_text"]})
            ann_id += 1
        det_payload = [{"transcription": field["rendered_text"], "points": [[field["bbox_xyxy"][0], field["bbox_xyxy"][1]], [field["bbox_xyxy"][2], field["bbox_xyxy"][1]], [field["bbox_xyxy"][2], field["bbox_xyxy"][3]], [field["bbox_xyxy"][0], field["bbox_xyxy"][3]]]} for field in record["fields"]]
        det_lines[split_of_id[image_id]].append(f"{record['image_path']}\t{json.dumps(det_payload, ensure_ascii=False)}")
        write_json(output_paths["kie"] / f"bizcard_{image_id}.json", record)

    for entry in rec_entries:
        rec_lines[split_of_id[entry["image_id"]]].append(f"{entry['crop_path']}\t{entry['text']}")

    write_json(output_paths["coco"] / "annotations_coco.json", coco)
    (output_paths["root"] / "classes.txt").write_text("\n".join(config["classes"]), encoding="utf-8")
    (output_paths["root"] / "data.yaml").write_text("\n".join(["# Korean Business Card Dataset Rebuild V2", f"path: {output_paths['root'].resolve()}", "train: splits/train.txt", "val: splits/val.txt", "test: splits/test.txt", f"nc: {len(config['classes'])}", f"names: {config['classes']}", ""]), encoding="utf-8")
    for split in ["train", "val", "test"]:
        (output_paths["det"] / f"{split}.txt").write_text("\n".join(det_lines[split]), encoding="utf-8")
        (output_paths["rec"] / f"{split}.txt").write_text("\n".join(rec_lines[split]), encoding="utf-8")


def run_generation(count: int, output_name: str, preview_count: int | None) -> Path:
    config = load_json(PROJECT_DIR / "configs" / "dataset_config.json")
    themes = load_json(PROJECT_DIR / "configs" / "themes.json")
    fonts_config = load_json(PROJECT_DIR / "configs" / "fonts.json")
    domain_map = load_json(PROJECT_DIR / "configs" / "company_domain_map.json")
    if preview_count is not None:
        config["preview_count"] = preview_count
    random.seed(config["seed"])

    output_dir = PROJECT_DIR / "output" / output_name
    output_paths = build_output_dirs(output_dir)
    font_manager = FontManager(fonts_config)
    data_factory = DataFactory(config, domain_map)
    classes = {name: idx for idx, name in enumerate(config["classes"])}
    records: dict[str, dict] = {}
    rec_entries: list[dict[str, str]] = []
    failed_log = output_paths["logs"] / "failed_samples.jsonl"

    for index in range(count):
        image_id = f"{index:05d}"
        try:
            image, record = generate_card(image_id, config, themes, font_manager, data_factory)
            image_path = output_paths["images"] / f"bizcard_{image_id}.jpg"
            image.save(image_path, "JPEG", quality=95)
            write_json(output_paths["master"] / f"bizcard_{image_id}.json", record)
            save_yolo(output_paths["labels_yolo"] / f"bizcard_{image_id}.txt", record, classes)
            if index < config["preview_count"]:
                draw_preview(image, record, output_paths["previews"] / f"preview_{image_id}.jpg")
            for field in record["fields"]:
                x1, y1, x2, y2 = field["bbox_xyxy"]
                crop_file = f"{field['field_id']}.jpg"
                crop_path = output_paths["rec_images"] / crop_file
                image.crop((x1, y1, x2, y2)).save(crop_path, "JPEG", quality=95)
                rec_entries.append({"image_id": image_id, "crop_path": f"rec/images/{crop_file}", "text": field["rendered_text"]})
            records[image_id] = record
        except Exception as exc:
            failure = {
                "image_id": image_id,
                "timestamp": datetime.now().isoformat(),
                "error": str(exc),
                "traceback": traceback.format_exc(),
            }
            with failed_log.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(failure, ensure_ascii=False) + "\n")

    splits = create_splits(list(records.keys()))
    export_all(output_paths, config, records, rec_entries, splits)
    summary = {
        "requested_count": count,
        "successful_count": len(records),
        "failed_count": count - len(records),
        "output_dir": str(output_dir.resolve()),
        "split_sizes": {name: len(ids) for name, ids in splits.items()},
        "created_at": datetime.now().isoformat(),
    }
    write_json(output_paths["logs"] / "run_summary.json", summary)
    validation = validate_output(output_dir, config["classes"], list(records.values()))
    write_json(output_paths["logs"] / "validation_summary.json", validation)
    write_validation_report(PROJECT_DIR / "docs" / "validation_report.md", output_dir, validation)
    return output_dir


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Korean Business Card Synthetic Dataset Generator V2")
    parser.add_argument("--count", type=int, default=None)
    parser.add_argument("--output-name", type=str, default="v2_base")
    parser.add_argument("--preview-count", type=int, default=None)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = load_json(PROJECT_DIR / "configs" / "dataset_config.json")
    count = args.count or config["default_count"]
    output_dir = run_generation(count, args.output_name, args.preview_count)
    print(f"Generated dataset at: {output_dir.resolve()}")


if __name__ == "__main__":
    main()
