#!/usr/bin/env python3
"""Sandbox copy-back for spec worktrees (AGENTS.md PS-7; called by scripts/spec-worktree.sh).

  seed  <main> <tree> <baseline>   record what the worktree's freshly copied .sayl/sandboxes holds
  check <main> <tree> <baseline>   exit 1 when the worktree holds sandbox work not yet copied back
  back  <main> <tree> <baseline>   copy the worktree's new/changed sandboxes, new _assets and a master
                                   change into main, merging sandboxes.json by entry; refuse on conflict

A sandbox counts as changed when its slug is new or its (current, updatedAt) moved since the baseline.
Main's .sayl is only written by `back`, and only for what the worktree changed.
"""
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

REG = "sandboxes.json"
ASSETS = "_assets"


def registry(sb: Path) -> list:
    return json.loads((sb / REG).read_text())["sandboxes"]


def state(entries: list) -> dict:
    return {e["slug"]: [e.get("current"), e.get("updatedAt")] for e in entries}


def master(entries: list):
    return next((e["slug"] for e in entries if e.get("master")), None)


def assets(sb: Path) -> dict:
    d = sb / ASSETS
    if not d.is_dir():
        return {}
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(d.iterdir()) if p.is_file()}


def snapshot(sb: Path) -> dict:
    entries = registry(sb)
    return {"sandboxes": state(entries), "master": master(entries), "assets": assets(sb)}


def pending(sb: Path, base: dict):
    now = snapshot(sb)
    changed = [s for s, v in now["sandboxes"].items() if base["sandboxes"].get(s) != v]
    new_assets = [a for a, h in now["assets"].items() if base["assets"].get(a) != h]
    master_moved = now["master"] != base["master"]
    return now, changed, new_assets, master_moved


def fail(msg: str, what: str = "sayl-back"):
    print(f"{what} refused: {msg}", file=sys.stderr)
    sys.exit(1)


def main():
    op, main_root, tree, base_path = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
    msb, wsb = main_root / ".sayl" / "sandboxes", tree / ".sayl" / "sandboxes"
    if op == "seed":
        base_path.write_text(json.dumps(snapshot(wsb)))
        return
    base = json.loads(base_path.read_text())
    now, changed, new_assets, master_moved = pending(wsb, base)
    if op == "check":
        if changed or new_assets or master_moved:
            fail(f"not copied back yet: sandboxes {changed}, assets {new_assets}, master moved {master_moved}; "
                 "run scripts/spec-worktree.sh sayl-back first", what="remove")
        return
    assert op == "back", op
    main_entries = registry(msb)
    main_state, main_assets = state(main_entries), assets(msb)
    # Conflicts first: nothing is written unless every change can land.
    for s in changed:
        if s in base["sandboxes"]:
            if main_state.get(s) != base["sandboxes"][s]:
                fail(f"sandbox {s} also changed in main since this worktree was seeded")
        elif s in main_state:
            fail(f"new sandbox {s} collides with a sandbox of that name in main")
    for a in new_assets:
        if a in base["assets"]:
            fail(f"asset {a} was overwritten in the worktree (assets are never overwritten; give it a new name)")
        if a in main_assets and main_assets[a] != now["assets"][a]:
            fail(f"asset {a} exists in main with different content")
    if master_moved and master(main_entries) != base["master"]:
        fail("the master changed in main since this worktree was seeded")

    staging = main_root / ".sayl" / ".sayl-back-tmp"
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir()
    for s in changed:
        tmp, dest, old = staging / s, msb / s, staging / f"{s}.old"
        shutil.copytree(wsb / s, tmp, symlinks=True)
        if dest.exists():
            dest.rename(old)
        tmp.rename(dest)
    for a in new_assets:
        if a not in main_assets:
            tmp = staging / a
            shutil.copy2(wsb / ASSETS / a, tmp)
            tmp.rename(msb / ASSETS / a)
    wt_by_slug = {e["slug"]: e for e in registry(wsb)}
    merged = [dict(wt_by_slug[e["slug"]]) if e["slug"] in changed else e for e in main_entries]
    merged += [dict(wt_by_slug[s]) for s in changed if s not in main_state]
    if master_moved:
        for e in merged:
            e["master"] = e["slug"] == now["master"]
    data = json.loads((msb / REG).read_text())
    data["sandboxes"] = merged
    tmp_reg = msb / f"{REG}.tmp"
    tmp_reg.write_text(json.dumps(data, indent=2) + "\n")
    os.replace(tmp_reg, msb / REG)
    shutil.rmtree(staging, ignore_errors=True)
    base_path.write_text(json.dumps(now))
    print(f"copied back: sandboxes {changed}, assets {new_assets}, master moved {master_moved}")


if __name__ == "__main__":
    main()
