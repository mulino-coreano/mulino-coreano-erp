#!/usr/bin/env bash
# Ranks the open, unblocked "Todo" issues on the Mulino Coreano board.
# Prints most-actionable first. Reads only — claims nothing.
set -euo pipefail

OWNER=${MULINO_PROJECT_OWNER:-mulino-coreano}
NUMBER=${MULINO_PROJECT_NUMBER:-1}

board=$(gh project item-list "$NUMBER" --owner "$OWNER" --limit 200 --format json)
issues=$(gh issue list --state open --limit 200 \
  --json number,title,labels,milestone,assignees)

BOARD="$board" ISSUES="$issues" python3 - <<'PY'
import json, os, re

board = json.loads(os.environ["BOARD"])["items"]
issues = {i["number"]: i for i in json.loads(os.environ["ISSUES"])}

# Board Status is automation-owned; it decides what is available, not the labels.
todo = {
    it["content"]["number"]: it
    for it in board
    if it.get("status") == "Todo" and it.get("content", {}).get("type") == "Issue"
}

PRIORITY = {"priority: high": 0, "priority: medium": 1, "priority: low": 2}
SKIP = {"blocked", "duplicate"}


def phase(issue):
    ms = (issue.get("milestone") or {}).get("title") or ""
    m = re.search(r"Phase\s+(\d+)", ms)
    return int(m.group(1)) if m else 99


rows = []
for number, item in todo.items():
    issue = issues.get(number)
    if not issue:
        continue
    labels = {lbl["name"] for lbl in issue["labels"]}
    if labels & SKIP:
        continue
    priority = min((PRIORITY[l] for l in labels if l in PRIORITY), default=3)
    rows.append((phase(issue), priority, number, issue, item, labels))

rows.sort(key=lambda r: (r[0], r[1], r[2]))

if not rows:
    print("No unblocked Todo issues on the board.")
    raise SystemExit(0)

for rank, (ph, pr, number, issue, item, labels) in enumerate(rows, 1):
    ms = (issue.get("milestone") or {}).get("title") or "no milestone"
    who = ", ".join(a["login"] for a in issue["assignees"]) or "unassigned"
    mark = "->" if rank == 1 else "  "
    print(f"{mark} #{number}  {issue['title']}")
    print(f"     {ms} | {' '.join(sorted(labels)) or 'no labels'} | {who}")
    print(f"     layer={item.get('layer', '-')} | stage={item.get('work Stage', '-')} "
          f"| gate={item.get('verification Gate', '-')}")
PY
