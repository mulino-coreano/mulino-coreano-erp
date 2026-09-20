#!/usr/bin/env bash
# Phase-level picture of the project: goals per milestone, what is in flight,
# what is blocked, and what is not tracked. Reads only — changes nothing.
set -euo pipefail

OWNER=${MULINO_PROJECT_OWNER:-mulino-coreano}
NUMBER=${MULINO_PROJECT_NUMBER:-1}
REPO=${MULINO_REPO:-mulino-coreano/mulino-coreano-erp}

MILESTONES=$(gh api "repos/$REPO/milestones?state=all&per_page=100") \
ISSUES=$(gh issue list --repo "$REPO" --state open --limit 200 \
  --json number,title,labels,milestone,assignees) \
BOARD=$(gh project item-list "$NUMBER" --owner "$OWNER" --limit 200 --format json) \
python3 - <<'PY'
import json, os, re

milestones = json.loads(os.environ["MILESTONES"])
issues = json.loads(os.environ["ISSUES"])
board = json.loads(os.environ["BOARD"])["items"]

status = {}
for it in board:
    c = it.get("content") or {}
    if c.get("number"):
        status[(c.get("type"), c["number"])] = it.get("status")


def phase_no(title):
    m = re.search(r"Phase\s+(\d+)", title or "")
    return int(m.group(1)) if m else 99


def line(issue):
    n = issue["number"]
    labels = {l["name"] for l in issue["labels"]}
    st = status.get(("Issue", n)) or "NOT ON BOARD"
    flags = []
    if "blocked" in labels:
        flags.append("blocked")
    if "duplicate" in labels:
        flags.append("duplicate")
    prio = next((l.replace("priority: ", "") for l in labels
                 if l.startswith("priority: ")), "-")
    who = ", ".join(a["login"] for a in issue["assignees"]) or "unassigned"
    tail = f" [{' '.join(flags)}]" if flags else ""
    return f"   {st:<13} #{n:<4} {issue['title']}\n" \
           f"   {'':<13} prio={prio} | {who}{tail}"


by_ms = {}
for i in issues:
    key = (i.get("milestone") or {}).get("title")
    by_ms.setdefault(key, []).append(i)

for ms in sorted(milestones, key=lambda m: phase_no(m["title"])):
    title = ms["title"]
    print(f"\n{title}   open {ms['open_issues']} / closed {ms['closed_issues']}"
          f"   [{ms['state']}]")
    rows = by_ms.get(title, [])
    if not rows:
        print("   (no open issues)")
    for i in sorted(rows, key=lambda x: x["number"]):
        print(line(i))

loose = by_ms.get(None, [])
if loose:
    print("\n마일스톤 없음 — Phase 목표에 묶이지 않은 이슈:")
    for i in sorted(loose, key=lambda x: x["number"]):
        print(line(i))

prs = [(n, st) for (t, n), st in status.items() if t == "PullRequest"]
if prs:
    print("\n보드 위의 PR:")
    for n, st in sorted(prs):
        print(f"   {st:<13} #{n}")
PY
