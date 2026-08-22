"""Where an evaluation run may write, for the Python harnesses.

The same rule the Kotlin side enforces, stated once for Python so the two cannot drift:

* anything that already exists under ``tools/agent_eval/results`` is read-only;
* a run writes into a directory it created for that run, which must not already exist;
* containment is decided on the resolved path, so ``..``, an absolute path and a symlink all
  resolve before the check;
* reading is unrestricted.

The desktop Gemma runners defaulted their ``--out`` to historical directories —
``pre_device_completion``, ``pre_device_v2/actual_gemma``, ``pre_device_v3/actual_gemma`` — so simply
running one replaced the record of an earlier evaluation. There are no cycle names here on purpose: a
rule that has to be edited every cycle will be forgotten one cycle.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RESULTS_ROOT = REPO_ROOT / "tools" / "agent_eval" / "results"


class ProtectedOutputError(RuntimeError):
    """Raised instead of writing somewhere that would replace a recorded result."""


def _resolved(path: Path) -> Path:
    # strict=False so a destination that does not exist yet still resolves its parents, which is
    # what makes `..` and symlinks collapse before the containment test.
    return Path(os.path.realpath(str(path)))


def is_protected(path: Path) -> bool:
    """True when writing to *path* would touch something already inside the results tree."""
    root = _resolved(RESULTS_ROOT)
    target = _resolved(Path(path))
    if target == root:
        return True
    if root not in target.parents:
        return False
    return not any(created == target or created in target.parents for created in _created_this_run)


def require_writable(path: Path) -> Path:
    """Return *path* when writing to it is allowed; raise otherwise.

    An exception rather than a silent redirect: a run pointed at a historical directory has a
    configuration bug, and quietly writing elsewhere hides it.
    """
    if is_protected(path):
        raise ProtectedOutputError(
            f"refusing to write inside the evaluation results tree: {_resolved(Path(path))}. "
            "Existing results are read-only. Pass --output-dir with a fresh directory, or call "
            "new_run_directory(label)."
        )
    return Path(path)


_created_this_run: list[Path] = []
_counter = {"n": 0}
_stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")


def new_run_directory(label: str) -> Path:
    """Create and return a directory for one run.

    The name carries the label, a timestamp and a counter, so two runs never share a directory —
    reusing one is what silently replaced the previous run's evidence.
    """
    safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in label)
    while True:
        _counter["n"] += 1
        candidate = RESULTS_ROOT / safe / f"run_{_stamp}_{_counter['n']}"
        if not candidate.exists():
            candidate.mkdir(parents=True)
            _created_this_run.append(_resolved(candidate))
            return candidate


def require_fresh_run_directory(directory: Path) -> Path:
    """Register a caller-supplied directory as this run's output, refusing an existing one."""
    directory = Path(directory)
    if directory.exists():
        raise ProtectedOutputError(
            f"run directory {directory} already exists; a run must not write over an earlier run's "
            "evidence. Choose a new directory or call new_run_directory(label)."
        )
    directory.mkdir(parents=True)
    _created_this_run.append(_resolved(directory))
    return directory


def resolve_output_dir(explicit: str | os.PathLike[str] | None, label: str) -> Path:
    """The output directory for a run: the explicit one if given, otherwise a fresh one."""
    if explicit is None:
        return new_run_directory(label)
    path = Path(explicit)
    if path.exists():
        return require_writable(path)
    return require_fresh_run_directory(path)


def promote(source: Path, destination: Path) -> None:
    """Publish *source* into the results tree, refusing to replace anything already there."""
    source, destination = Path(source), Path(destination)
    if not source.exists():
        raise ProtectedOutputError(f"nothing to promote at {source}")
    if destination.exists():
        raise ProtectedOutputError(
            f"refusing to promote onto {destination}, which already exists. Publishing must never "
            "replace a recorded result."
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())
