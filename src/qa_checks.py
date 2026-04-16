from __future__ import annotations

import json
from pathlib import Path


def validate_output(output_dir: Path, class_names: list[str], master_records: list[dict]) -> dict:
    image_dir = output_dir / "images"
    yolo_dir = output_dir / "labels_yolo"
    master_dir = output_dir / "master_annotations"
    previews_dir = output_dir / "previews"

    image_count = len(list(image_dir.glob("*.jpg")))
    yolo_count = len(list(yolo_dir.glob("*.txt")))
    master_count = len(list(master_dir.glob("*.json")))
    preview_count = len(list(previews_dir.glob("*.jpg")))

    invalid_boxes = []
    empty_text_fields = 0
    class_counts = {name: 0 for name in class_names}
    layout_counts = {}
    orientation_counts = {}
    theme_counts = {}

    for record in master_records:
        orientation = record["card_orientation"]
        layout_type = record["layout_type"]
        theme_name = record["theme_name"]

        orientation_counts[orientation] = orientation_counts.get(orientation, 0) + 1
        layout_counts[layout_type] = layout_counts.get(layout_type, 0) + 1
        theme_counts[theme_name] = theme_counts.get(theme_name, 0) + 1

        width = record["image_size"]["width"]
        height = record["image_size"]["height"]
        for field in record["fields"]:
            class_name = field["field_class"]
            class_counts[class_name] = class_counts.get(class_name, 0) + 1
            if not field["rendered_text"].strip():
                empty_text_fields += 1
            x1, y1, x2, y2 = field["bbox_xyxy"]
            if x2 <= x1 or y2 <= y1 or x1 < 0 or y1 < 0 or x2 > width or y2 > height:
                invalid_boxes.append(
                    {
                        "image_id": record["image_id"],
                        "field_id": field["field_id"],
                        "bbox_xyxy": field["bbox_xyxy"]
                    }
                )

    return {
        "counts": {
            "images": image_count,
            "labels_yolo": yolo_count,
            "master_annotations": master_count,
            "previews": preview_count
        },
        "consistency": {
            "images_match_labels": image_count == yolo_count,
            "images_match_master": image_count == master_count,
            "invalid_bbox_count": len(invalid_boxes),
            "empty_text_field_count": empty_text_fields
        },
        "class_counts": class_counts,
        "layout_counts": layout_counts,
        "orientation_counts": orientation_counts,
        "theme_counts": theme_counts,
        "invalid_bbox_samples": invalid_boxes[:20]
    }


def write_validation_report(report_path: Path, output_dir: Path, summary: dict) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Validation Report",
        "",
        f"- Output: `{output_dir}`",
        f"- Images: `{summary['counts']['images']}`",
        f"- YOLO labels: `{summary['counts']['labels_yolo']}`",
        f"- Master annotations: `{summary['counts']['master_annotations']}`",
        f"- Previews: `{summary['counts']['previews']}`",
        f"- Images match labels: `{summary['consistency']['images_match_labels']}`",
        f"- Images match master: `{summary['consistency']['images_match_master']}`",
        f"- Invalid bbox count: `{summary['consistency']['invalid_bbox_count']}`",
        f"- Empty rendered text count: `{summary['consistency']['empty_text_field_count']}`",
        "",
        "## Orientation Counts",
        ""
    ]

    for key, value in sorted(summary["orientation_counts"].items()):
        lines.append(f"- `{key}`: `{value}`")

    lines.extend(["", "## Layout Counts", ""])
    for key, value in sorted(summary["layout_counts"].items()):
        lines.append(f"- `{key}`: `{value}`")

    lines.extend(["", "## Theme Counts", ""])
    for key, value in sorted(summary["theme_counts"].items()):
        lines.append(f"- `{key}`: `{value}`")

    lines.extend(["", "## Class Counts", ""])
    for key, value in sorted(summary["class_counts"].items()):
        lines.append(f"- `{key}`: `{value}`")

    if summary["invalid_bbox_samples"]:
        lines.extend(["", "## Invalid BBox Samples", ""])
        for item in summary["invalid_bbox_samples"]:
            lines.append(f"- `{json.dumps(item, ensure_ascii=False)}`")

    report_path.write_text("\n".join(lines), encoding="utf-8")
