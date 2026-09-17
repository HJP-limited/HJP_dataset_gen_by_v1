#!/usr/bin/env python3
"""Compute the four official metrics from the replay record.

Nothing here re-runs the agent. The replay wrote what happened; this reads it, so the scoring can
be corrected and re-run without changing a single agent decision.
"""
from __future__ import annotations

import argparse, json, re
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

TOOLS = ["search_contacts", "get_contact", "update_business_card",
         "create_calendar_event", "open_compose", "get_current_datetime"]

def canon_email(v): return (v or "").strip().lower()
def canon_phone(v): return re.sub(r"\D", "", v or "")
def canon_dt(v):
    s = (v or "").strip().replace(" ", "T")
    m = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})", s)
    return f"{m.group(1)}T{m.group(2)}" if m else s


def resolve_relative(spec, anchor_date=None):
    """next_tuesday_15:00 / tomorrow_10:00 -> the absolute local datetime the run should produce."""
    if anchor_date is None:
        return None
    if spec == "tomorrow_10:00":
        return (anchor_date + timedelta(days=1)).strftime("%Y-%m-%d") + "T10:00"
    if spec == "next_tuesday_15:00":
        ahead = (1 - anchor_date.weekday()) % 7 or 7
        return (anchor_date + timedelta(days=ahead + 7 if ahead < 3 else ahead)).strftime("%Y-%m-%d") + "T15:00"
    return None


def field_ok(spec, present, value, observed_turn=None, temporal_anchor=None):
    """One argument field against its declared comparator. Returns (scored, ok)."""
    cmp_ = spec["cmp"]
    if cmp_ == "optional_int_range":
        if not present: return True, True
        try: n = int(value)
        except Exception: return True, False
        return True, spec["min"] <= n <= spec["max"]
    if not present:
        return True, False
    if cmp_ == "exact": return True, value == spec["value"]
    if cmp_ == "ordinal_candidate":
        # The persisted candidate order in the observed search result is the
        # authoritative ordinal source.  This avoids baking a stale card id into
        # Gold while still checking the actual argument exactly.
        if not observed_turn: return True, False
        candidates = observed_turn.get("candidate_card_ids") or []
        pos = int(spec["position"]) - 1
        return True, 0 <= pos < len(candidates) and value == candidates[pos]
    if cmp_ == "exact_one_of": return True, value in spec["value"]
    if cmp_ == "contains_all":
        s = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
        return True, all(tok in s for tok in spec["value"])
    if cmp_ == "canonical_email": return True, canon_email(value) == canon_email(spec["value"])
    if cmp_ == "canonical_phone": return True, canon_phone(value) == canon_phone(spec["value"])
    if cmp_ == "canonical_datetime": return True, canon_dt(value) == canon_dt(spec["value"])
    if cmp_ == "relative_datetime":
        want = resolve_relative(spec["spec"], temporal_anchor)
        return True, (want is not None and canon_dt(value) == want)
    if cmp_ == "present_string":
        return True, isinstance(value, str) and bool(value.strip())
    if cmp_ == "updates_subset":
        if not isinstance(value, dict): return True, False
        return True, all(str(value.get(k, "")).strip() == v for k, v in spec["value"].items())
    return True, False


def score_call(expected, observed_args, observed_turn=None, temporal_anchor=None):
    """Strict exact match plus field-level detail for one correctly-selected call."""
    fields = {}
    for name, spec in expected["args"].items():
        present = name in observed_args
        scored, ok = field_ok(spec, present, observed_args.get(name), observed_turn, temporal_anchor)
        fields[name] = {"expected": spec, "observed": observed_args.get(name),
                        "present": present, "match": ok}
    strict = all(f["match"] for f in fields.values())
    return strict, fields


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="."); ap.add_argument("--set", required=True)
    ap.add_argument("--raw", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--trace-out")
    ap.add_argument("--artifact-index", help="final artifact index used to read scenario-local get_current_datetime results")
    a = ap.parse_args(); repo = Path(a.repo).resolve()
    gold = {s["scenario_id"]: s for s in
            json.loads((repo / a.set).read_text(encoding="utf-8"))["scenarios"]}
    observed = {}
    for line in (repo / a.raw).read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line); observed[row["scenario_id"]] = row

    # Relative-date scoring is anchored exclusively to each scenario's recorded
    # get_current_datetime ToolResult.  Never use host wall-clock time.
    temporal_anchors = {}
    if a.artifact_index:
        index = json.loads(Path(a.artifact_index).read_text(encoding="utf-8"))
        for item in index.get("scenarios", []):
            proto = Path(item["artifact_path"]) / "native_protocol.local.jsonl"
            if not proto.exists():
                continue
            for line in proto.read_text(encoding="utf-8").splitlines():
                try: rec = json.loads(line)
                except Exception: continue
                if rec.get("phase") != "send_return" or rec.get("model_tool_name") != "get_current_datetime":
                    continue
                try:
                    payload = json.loads(rec.get("raw_input", "{}"))
                    dt = payload.get("data", {}).get("datetime")
                    if dt:
                        temporal_anchors[item["scenario_id"]] = datetime.fromisoformat(dt).date()
                        break
                except Exception:
                    continue

    dp_total = dp_correct = 0
    call_total = call_correct_tool = 0
    arg_strict_pass = arg_scored = 0
    field_pass = field_total = 0
    e2e_pass = 0
    per_tool_sel = defaultdict(lambda: [0, 0])
    per_tool_arg = defaultdict(lambda: [0, 0])
    per_split = defaultdict(lambda: Counter())
    per_bucket = defaultdict(lambda: Counter())
    per_workflow = defaultdict(lambda: Counter())
    per_category = defaultdict(lambda: Counter())
    failures = Counter()
    traces = []
    success_count = 0

    def bucket(n):
        return "2" if n == 2 else "3-4" if n <= 4 else "5-6" if n <= 6 else "7-8" if n <= 8 else "9-12"

    for sid, g in gold.items():
        o = observed.get(sid)
        trace = {"scenario_id": sid, "split": g["split"], "category": g["category"],
                 "workflow": g["workflow"], "target_card_ids": g["target_card_ids"],
                 "decision_points": [], "tool_calls": []}
        if o is None:
            failures["기타"] += 1
            trace["task_success"] = False; trace["failure_reason"] = "no replay record"
            traces.append(trace); continue

        scen_fail = []
        obs_turns = {t["index"]: t for t in o["turns"]}
        all_tools = []
        for spec in g["turns"]:
            idx = spec["index"]; ot = obs_turns.get(idx)
            got = ot["executed_tools"] if ot else []
            all_tools += got
            forbidden_here = set(spec.get("forbidden_tools", []))
            if forbidden_here.intersection(got):
                scen_fail.append("Turn-scoped forbidden tool")
            dp_total += 1
            correct = got in spec["allowed_tool_sequences"]
            if correct: dp_correct += 1
            else:
                if spec["decision_kind"] == "NO_TOOL" and got:
                    scen_fail.append("불필요한 Tool 호출")
                elif spec["decision_kind"] == "TOOL" and not got:
                    scen_fail.append("필요한 Tool 미호출")
                else:
                    scen_fail.append("Tool Selection 오류")
            trace["decision_points"].append({
                "turn": idx, "user": spec["user"], "note": spec["note"],
                "reference_turn": spec.get("reference_turn"),
                "expected_allowed": spec["allowed_tool_sequences"],
                "predicted": got, "tool_selection_correct": correct,
                "selected_card_id": ot["selected_card_id"] if ot else "",
                "answer": (ot["answer"][:160] if ot else "")})

            # Argument scoring uses the declared alternative for a direct unique-name read when
            # that route was selected; the primary search-first declaration remains available.
            call_specs = spec.get("alternative_expected_calls", []) if got == ["get_contact"] and spec.get("alternative_expected_calls") else spec["expected_calls"]
            # argument scoring: only for calls the gold specifies, and only when the tool matched
            used = []
            for exp in call_specs:
                call_total += 1
                cand = None
                for j, c in enumerate(ot["tool_arguments"] if ot else []):
                    if j in used: continue
                    if c["tool"] == exp["tool"]: cand = (j, c); break
                if cand is None:
                    per_tool_sel[exp["tool"]][1] += 1
                    trace["tool_calls"].append({"turn": idx, "tool": exp["tool"],
                                                "expected_arguments": exp["args"],
                                                "predicted_arguments": None,
                                                "tool_selected": False,
                                                "argument_exact_match": False,
                                                "end_to_end_call_correct": False})
                    continue
                j, c = cand; used.append(j)
                call_correct_tool += 1
                per_tool_sel[exp["tool"]][0] += 1; per_tool_sel[exp["tool"]][1] += 1
                strict, fields = score_call(exp, c["arguments"], ot, temporal_anchors.get(sid))
                arg_scored += 1
                if strict: arg_strict_pass += 1; e2e_pass += 1
                else: scen_fail.append("Argument 오류")
                per_tool_arg[exp["tool"]][1] += 1
                if strict: per_tool_arg[exp["tool"]][0] += 1
                for f in fields.values():
                    field_total += 1
                    if f["match"]: field_pass += 1
                trace["tool_calls"].append({"turn": idx, "tool": exp["tool"],
                                            "expected_arguments": exp["args"],
                                            "predicted_arguments": c["arguments"],
                                            "tool_selected": True,
                                            "argument_exact_match": strict,
                                            "field_comparison": fields,
                                            "end_to_end_call_correct": strict})

        # ---- task success -------------------------------------------------------------------
        s = g["success"]; ok = True; why = []
        for t in s.get("must_call", []):
            satisfied = t in all_tools
            # Policy A permits a direct unique-name get_contact in place of the
            # search-first primary path.  Treat that declared alternative as
            # satisfying the scenario-level requirement as well as the turn score.
            if not satisfied and t == "search_contacts":
                for spec in g["turns"]:
                    if spec.get("alternative_expected_calls") and any(
                        alt.get("tool") == "get_contact" for alt in spec["alternative_expected_calls"]
                    ):
                        ot = obs_turns.get(spec["index"])
                        if ot and ot.get("executed_tools") == ["get_contact"]:
                            satisfied = True
                            break
            if not satisfied:
                ok = False; why.append(f"missing {t}"); scen_fail.append("필요한 Tool 미호출")
        # `must_not_call` is represented on each turn as `forbidden_tools`; applying the legacy
        # scenario-global list here incorrectly rejects valid later compose/calendar turns.
        if "final_target_card_id" in s and o["final_selected_card_id"] != s["final_target_card_id"]:
            ok = False; why.append(f"target {o['final_selected_card_id'] or 'none'} != {s['final_target_card_id']}")
            scen_fail.append("Reference / 대상 선택 오류")
        policy = s.get("final_target_policy")
        if policy:
            kind = policy.get("kind")
            final = o.get("final_selected_card_id", "")
            if kind == "explicit_new_target_after_turn":
                prior_turn = obs_turns.get(int(policy.get("must_differ_from_target_turn", 0)))
                prior = (prior_turn or {}).get("selected_card_id", "")
                if not final or (prior and final == prior):
                    ok = False; why.append("explicit target transition not reflected"); scen_fail.append("Reference / 대상 선택 오류")
            elif kind == "ordinal_candidate":
                ot_sel = obs_turns.get(int(policy["turn"]))
                candidates = (ot_sel or {}).get("candidate_card_ids") or []
                pos = int(policy["position"]) - 1
                expected_final = candidates[pos] if 0 <= pos < len(candidates) else None
                if not expected_final or final != expected_final:
                    ok = False; why.append("ordinal final target mismatch"); scen_fail.append("Reference / 대상 선택 오류")
        if "must_not_final_target" in s and o["final_selected_card_id"] == s["must_not_final_target"]:
            ok = False; why.append("stale target retained"); scen_fail.append("Context / 멀티턴 기억 오류")
        # `open_compose` and `create_calendar_event` only open an external draft surface;
        # they do not send/save.  no_side_effect therefore rejects durable mutations (and any
        # future explicit send/save markers), but not draft-surface records.
        if s.get("no_side_effect") and o["mutated_cards"]:
            ok = False; why.append("unexpected side effect"); scen_fail.append("Side-effect 정책 오류")
        if "compose_to" in s:
            hit = any(canon_email(d["to"]) == canon_email(s["compose_to"])
                      and d["channel"].lower() == s["compose_channel"]
                      for t in o["turns"] for d in t["new_compose_drafts"])
            if not hit: ok = False; why.append("compose recipient/channel"); scen_fail.append("Argument 오류")
        if "update_card_id" in s:
            titles = {r["card_id"]: r for r in o.get("mutated_card_titles", [])}
            row = titles.get(s["update_card_id"])
            good = row is not None and all(str(row.get(k, "")) == v for k, v in s["update_fields"].items())
            if not good: ok = False; why.append("update not applied"); scen_fail.append("Argument 오류")
        if "clarification_expected_turn" in s:
            ct = s["clarification_expected_turn"]; ot = obs_turns.get(ct)
            if ot and ot["executed_tools"]:
                ok = False; why.append("clarification turn called a tool"); scen_fail.append("Clarification 오류")
        for ct in s.get("clarification_expected_turns", []):
            ot = obs_turns.get(ct)
            if ot and ot.get("executed_tools"):
                ok = False; why.append(f"clarification turn {ct} called a tool"); scen_fail.append("Clarification 오류")
        if "missing_field" in s:
            if any(t["new_compose_drafts"] for t in o["turns"]):
                ok = False; why.append("composed despite missing field"); scen_fail.append("Missing-field 처리 오류")

        if ok: success_count += 1
        else:
            for f in dict.fromkeys(scen_fail): failures[f] += 1
            if not scen_fail: failures["최종 응답 / 상태 불일치"] += 1
        trace["task_success"] = ok
        trace["failure_reason"] = "; ".join(why[:6])
        trace["final_state"] = {"selected_card_id": o["final_selected_card_id"],
                                "compose_drafts": o["compose_draft_count"],
                                "calendar_drafts": o["calendar_draft_count"],
                                "mutated_cards": o["mutated_cards"]}
        traces.append(trace)
        for agg, key in ((per_split, g["split"]), (per_bucket, bucket(g["turn_count"])),
                         (per_workflow, g["workflow"]), (per_category, g["category"])):
            agg[key]["total"] += 1
            if ok: agg[key]["success"] += 1

    def rate(n, d): return round(n / d, 4) if d else None

    report = {
        "schema": "hjp_multiturn_toolcall_eval_report/v1",
        "eval_set": a.set, "raw": a.raw,
        "headline": {
            "total_scenarios": len(gold),
            "task_success_rate": rate(success_count, len(gold)),
            "task_success_count": success_count,
            "total_tool_decision_points": dp_total,
            "tool_selection_accuracy": rate(dp_correct, dp_total),
            "tool_selection_correct": dp_correct,
            "correct_tool_calls": call_correct_tool,
            "expected_tool_calls": call_total,
            "argument_exact_match_accuracy": rate(arg_strict_pass, arg_scored),
            "argument_exact_match_pass": arg_strict_pass,
            "argument_field_accuracy": rate(field_pass, field_total),
            "argument_fields_pass": field_pass, "argument_fields_total": field_total,
            "end_to_end_tool_call_accuracy": rate(e2e_pass, call_total),
            "end_to_end_pass": e2e_pass,
        },
        "by_split": {k: {"total": v["total"], "success": v["success"],
                         "tsr": rate(v["success"], v["total"])} for k, v in per_split.items()},
        "by_turn_bucket": {k: {"total": v["total"], "success": v["success"],
                               "tsr": rate(v["success"], v["total"])} for k, v in per_bucket.items()},
        "by_workflow": {k: {"total": v["total"], "success": v["success"],
                            "tsr": rate(v["success"], v["total"])} for k, v in per_workflow.items()},
        "by_category": {k: {"total": v["total"], "success": v["success"],
                            "tsr": rate(v["success"], v["total"])} for k, v in per_category.items()},
        "tool_selection_by_tool": {k: {"correct": v[0], "total": v[1], "accuracy": rate(v[0], v[1])}
                                   for k, v in sorted(per_tool_sel.items())},
        "argument_accuracy_by_tool": {k: {"pass": v[0], "total": v[1], "accuracy": rate(v[0], v[1])}
                                      for k, v in sorted(per_tool_arg.items())},
        "failure_taxonomy": dict(failures.most_common()),
    }
    out = repo / a.out; out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=False) + "\n")
    if a.trace_out:
        t = repo / a.trace_out; t.parent.mkdir(parents=True, exist_ok=True)
        with t.open("w", encoding="utf-8") as sink:
            for row in traces:
                sink.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps(report["headline"], ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
