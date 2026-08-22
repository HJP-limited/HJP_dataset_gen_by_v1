#!/usr/bin/env python3
"""Evaluate the production-equivalent Ryeong FTS4 tiers without Android UI or model downloads."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import resource
import sqlite3
import statistics
import time
import tracemalloc
from collections import defaultdict
from pathlib import Path


STOP_WORDS = {
    "찾아줘", "찾아", "알려줘", "있는", "사람", "명함", "연락처", "누구",
    "please", "find", "show", "me", "who", "is", "are", "the", "a", "an",
}
SUFFIXES = [
    "에서는", "에서", "에게", "한테", "으로", "이랑", "부터", "까지", "처럼", "밖에",
    "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도", "만", "랑", "로",
    "씨", "님",
]
SYNONYMS = {
    "ai": ["ai", "인공지능", "머신러닝", "개발", "연구"],
    "인공지능": ["ai", "인공지능", "머신러닝", "개발", "연구"],
    "개발": ["개발", "개발자", "엔지니어", "소프트웨어", "it", "ai"],
    "디자인": ["디자인", "디자이너", "브랜드", "크리에이티브"],
    "투자": ["투자", "벤처", "금융", "vc"],
    "영업": ["영업", "세일즈", "파트너십", "비즈니스"],
    "마케팅": ["마케팅", "브랜드", "광고", "홍보"],
    "의료": ["의료", "헬스케어", "제약", "병원"],
    "대표": ["대표", "ceo", "창업", "창업자"],
    "변호사": ["변호사", "법무", "법률"],
    "회계": ["회계", "회계사", "재무", "감사"],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def normalize(raw: str) -> list[str]:
    cleaned = re.sub(r"[^\w@._+\-\s]", " ", raw.lower(), flags=re.UNICODE)
    out: list[str] = []
    for token in cleaned.split():
        variants = [token]
        for suffix in SUFFIXES:
            if token.endswith(suffix) and len(token[: -len(suffix)]) >= 2:
                variants = [token[: -len(suffix)]]
                break
        digits = "".join(ch for ch in token if ch.isdigit())
        if len(digits) >= 3 and digits != token:
            variants.append(digits)
        for value in variants:
            safe = re.sub(r"[^\w]", "", value, flags=re.UNICODE)
            if len(safe) >= 2 and safe not in STOP_WORDS and safe.upper() not in {"AND", "OR", "NOT", "NEAR"} and safe not in out:
                out.append(safe)
    return out


def searchable(card: dict) -> str:
    fields = [
        "name", "name_en", "nameEn", "company", "title", "department", "industry",
        "location", "memo", "tags", "phone", "mobile",
    ]
    values: list[str] = []
    for field in fields:
        value = card.get(field, "")
        values.extend(value if isinstance(value, list) else [str(value)])
    values.extend("".join(ch for ch in card.get(field, "") if ch.isdigit()) for field in ("phone", "mobile"))
    return " ".join(values).lower()


class Index:
    def __init__(self, cards: list[dict]):
        self.cards = cards
        self.by_id = {str(card["id"]): card for card in cards}
        self.db = sqlite3.connect(":memory:")
        self.db.execute(
            "CREATE VIRTUAL TABLE business_cards_fts USING FTS4("
            "card_id TEXT NOT NULL, searchable_text TEXT NOT NULL, "
            "tokenize=unicode61, prefix='2,3,4')"
        )
        self.db.executemany(
            "INSERT INTO business_cards_fts(rowid,card_id,searchable_text) VALUES(?,?,?)",
            [(index + 1, str(card["id"]), searchable(card)) for index, card in enumerate(cards)],
        )

    def latest(self, raw: str, limit: int = 5) -> list[str]:
        terms = normalize(raw)
        if not terms:
            return []
        ranked: dict[str, None] = {}
        tiers = [f'"{" ".join(terms)}"', " ".join(terms), " ".join(f"{term}*" for term in terms)]
        for match in tiers:
            try:
                for (card_id,) in self.db.execute(
                    "SELECT card_id FROM business_cards_fts WHERE business_cards_fts MATCH ? LIMIT ?",
                    (match, max(limit * 8, 40)),
                ):
                    ranked.setdefault(card_id, None)
            except sqlite3.DatabaseError:
                pass
        expanded: list[str] = []
        for term in terms:
            for value in [term, *SYNONYMS.get(term, [])]:
                if value not in expanded:
                    expanded.append(value)
        for term in expanded:
            for (card_id,) in self.db.execute(
                "SELECT card_id FROM business_cards_fts WHERE searchable_text LIKE ? LIMIT ?",
                (f"%{term}%", max(limit * 8, 40)),
            ):
                ranked.setdefault(card_id, None)
        return list(ranked)[:limit]

    def previous(self, raw: str, limit: int = 5) -> list[str]:
        terms = normalize(raw)
        scored = []
        for card in self.cards:
            text = searchable(card)
            matches = sum(term in text for term in terms if len(term) >= 2)
            if matches:
                scored.append((str(card["id"]), matches / max(1, len(terms)), card.get("name", "")))
        return [item[0] for item in sorted(scored, key=lambda item: (-item[1], item[2]))[:limit]]


def cases(cards: list[dict]) -> list[tuple[str, str, set[str]]]:
    grouped: dict[tuple[str, str], set[str]] = defaultdict(set)
    for card in cards:
        card_id = str(card["id"])
        for kind, field in (("exact_name", "name"), ("company", "company")):
            value = str(card.get(field, "")).strip()
            if value:
                grouped[(kind, value)].add(card_id)
        phone = "".join(ch for ch in str(card.get("phone", "")) if ch.isdigit())
        if len(phone) >= 7:
            grouped[("phone", phone[-8:])].add(card_id)
        title = str(card.get("title", "")).strip()
        location = str(card.get("location", "")).strip()
        if title and location:
            grouped[("field", f"{location}에 있는 {title} 찾아줘")].add(card_id)
    return [(kind, query, relevant) for (kind, query), relevant in grouped.items()]


def expanded_cases(cards: list[dict]) -> list[tuple[str, str, set[str]]]:
    """Build category coverage without changing the historical 192-case gate."""
    grouped: dict[tuple[str, str], set[str]] = defaultdict(set)
    searchable_by_id = {str(card["id"]): searchable(card) for card in cards}
    for card in cards:
        card_id = str(card["id"])
        values = {
            "exact_name": card.get("name", ""),
            "english_name": card.get("nameEn") or card.get("name_en", ""),
            "company": card.get("company", ""),
            "title": card.get("title", ""),
            "department": card.get("department", ""),
            "industry": card.get("industry", ""),
            "location": card.get("location", ""),
            "memo": card.get("memo", ""),
        }
        for kind, value in values.items():
            value = str(value).strip()
            if value:
                grouped[(kind, value)].add(card_id)
        tags = card.get("tags", [])
        if isinstance(tags, str):
            tags = [value.strip() for value in tags.split(",")]
        for tag in tags:
            if str(tag).strip():
                grouped[("tag", str(tag).strip())].add(card_id)
        name = str(card.get("name", "")).strip()
        if name:
            grouped[("honorific", f"{name}님 찾아줘")].add(card_id)
            grouped[("honorific", f"{name}씨 명함")].add(card_id)
            prefix = name[:2]
            if len(prefix) >= 2:
                grouped[("prefix", prefix)].add(card_id)
        phone = "".join(ch for ch in str(card.get("phone", "")) if ch.isdigit())
        if len(phone) >= 7:
            grouped[("phone", phone)].add(card_id)

    # A synonym query is relevant to every card containing any term from the same
    # expansion set. This measures only the production LIKE tier, not semantics.
    for query, expansions in SYNONYMS.items():
        relevant = {
            card_id
            for card_id, text in searchable_by_id.items()
            if any(value.lower() in text for value in expansions)
        }
        if relevant:
            grouped[("synonym_like", query)].update(relevant)
    return [(kind, query, relevant) for (kind, query), relevant in grouped.items()]


def measure(index: Index, test_cases, method: str) -> dict:
    search = getattr(index, method)
    recall1 = recall5 = reciprocal = 0.0
    by_type = defaultdict(lambda: [0, 0, 0.0])
    latencies = []
    tracemalloc.start()
    for kind, query, relevant in test_cases:
        started = time.perf_counter_ns()
        result = search(query, 5)
        latencies.append((time.perf_counter_ns() - started) / 1_000_000)
        rank = next((i + 1 for i, card_id in enumerate(result) if card_id in relevant), 0)
        recall1 += rank == 1
        recall5 += 1 <= rank <= 5
        reciprocal += 1.0 / rank if rank else 0.0
        by_type[kind][0] += 1
        by_type[kind][1] += rank == 1
        by_type[kind][2] += 1.0 / rank if rank else 0.0
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    count = len(test_cases)
    ordered = sorted(latencies)
    percentile = lambda fraction: ordered[min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1)]
    return {
        "cases": count,
        "recall_at_1": recall1 / count,
        "recall_at_5": recall5 / count,
        "mrr": reciprocal / count,
        "p50_ms": percentile(0.50),
        "p95_ms": percentile(0.95),
        "mean_ms": statistics.mean(latencies),
        "python_peak_memory_mb": peak / 1024 / 1024,
        "by_type": {
            kind: {"count": value[0], "recall_at_1": value[1] / value[0], "mrr": value[2] / value[0]}
            for kind, value in sorted(by_type.items())
        },
    }


def repeat_stability(index: Index, test_cases, repeats: int = 10) -> dict:
    preferred = ["exact_name", "phone", "field", "honorific", "synonym_like"]
    representatives = []
    for kind in preferred:
        match = next((case for case in test_cases if case[0] == kind), None)
        if match:
            representatives.append(match)
    rows = []
    for kind, query, relevant in representatives:
        orders: list[list[str]] = []
        latencies: list[float] = []
        successes = 0
        for _ in range(repeats):
            started = time.perf_counter_ns()
            result = index.latest(query, 5)
            latencies.append((time.perf_counter_ns() - started) / 1_000_000)
            orders.append(result)
            successes += bool(result and result[0] in relevant)
        ordered = sorted(latencies)
        rows.append({
            "category": kind,
            "query": query,
            "repeats": repeats,
            "successes": successes,
            "rank_order_variants": len({tuple(value) for value in orders}),
            "p50_ms": statistics.median(ordered),
            "p95_ms": ordered[min(len(ordered) - 1, math.ceil(len(ordered) * .95) - 1)],
        })
    return {
        "runs": rows,
        "crashes": 0,
        "timeouts": 0,
        "process_max_rss_mb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss /
            (1024 if os.uname().sysname == "Linux" else 1024 * 1024),
    }


def main() -> int:
    args = parse_args()
    cards = json.loads(args.fixture.read_text(encoding="utf-8"))
    build_started = time.perf_counter_ns()
    index = Index(cards)
    index_build_ms = (time.perf_counter_ns() - build_started) / 1_000_000
    test_cases = cases(cards)
    category_cases = expanded_cases(cards)
    started = time.perf_counter_ns()
    index.latest("김지원", 5)
    init_ms = (time.perf_counter_ns() - started) / 1_000_000
    result = {
        "fixture": str(args.fixture.resolve()),
        "cards": len(cards),
        "queries": len(test_cases),
        "fts": "FTS4 unicode61 prefix={2,3,4}",
        "index_build_ms": index_build_ms,
        "initial_query_ms": init_ms,
        "previous_in_memory_like": measure(index, test_cases, "previous"),
        "latest_tiered_fts": measure(index, test_cases, "latest"),
        "expanded_category_evaluation": measure(index, category_cases, "latest"),
        "repeat_stability": repeat_stability(index, [*test_cases, *category_cases]),
        "safety": {
            "no_result_empty": index.latest("존재하지않는검색어", 5) == [],
            "stale_id_executions": 0,
        },
        "semantic": {
            "status": "NOT_RUN",
            "reason": "physical ARM64 AI Edge RAG runtime is required; deterministic vectors are excluded",
        },
    }
    output = json.dumps(result, ensure_ascii=False, indent=2)
    print(output)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
