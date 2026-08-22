#!/usr/bin/env python3
"""Create a deterministic, ID-blinded human review worksheet."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path, nargs="+")
    parser.add_argument("--worksheet", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20260801)
    args = parser.parse_args()
    items = []
    for path in args.inputs:
        for line in path.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row.get("record_type") != "test_result":
                continue
            call = next((item for item in reversed(row.get("tool_calls") or [])
                         if item.get("name") == "open_compose"), None)
            if not call:
                continue
            values = call.get("arguments") or {}
            items.append({
                "case_id": row["test_id"], "source": str(path),
                "prompt": row["prompt"], "channel": values.get("channel", ""),
                "subject": values.get("subject", ""), "body": values.get("body", ""),
            })
    random.Random(args.seed).shuffle(items)
    mapping = {}
    worksheet = []
    for index, item in enumerate(items, 1):
        review_id = f"R{index:03d}-{hashlib.sha256((item['source'] + item['case_id']).encode()).hexdigest()[:6]}"
        mapping[review_id] = {"case_id": item.pop("case_id"), "source": item.pop("source")}
        worksheet.append({
            "review_id": review_id, **item,
            "naturalness_0_to_10": "", "factuality_0_to_10": "",
            "goal_coverage_0_to_10": "", "ready_to_use_0_to_10": "",
            "reviewer_notes": "",
        })
    args.worksheet.parent.mkdir(parents=True, exist_ok=True)
    with args.worksheet.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(worksheet[0]))
        writer.writeheader(); writer.writerows(worksheet)
    args.mapping.parent.mkdir(parents=True, exist_ok=True)
    args.mapping.write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"body_cases={len(items)} worksheet={args.worksheet}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
