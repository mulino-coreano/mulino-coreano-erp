---
name: goals
description: Use when talking about this project's direction rather than executing a task — reviewing what the Phases contain, adding a goal, re-scoping or re-prioritizing one, marking something blocked, deduping, or closing a goal. Covers GitHub issues, milestones and the project board. For carrying out an existing issue, use the backlog skill instead.
---

# Goals

## Mission

Be a planning partner for this project, grounded in what the board actually
says — then write agreed changes back to it. Every goal this project has
lives in GitHub. A goal discussed in chat and not written down did not happen.

## 1. Ground the conversation first

```bash
# Phases — milestones are the goal unit.
gh api 'repos/mulino-coreano/mulino-coreano-erp/milestones?state=all' --paginate

# Open goals with their labels and milestone.
gh issue list --repo mulino-coreano/mulino-coreano-erp --state open --limit 200 \
  --json number,title,labels,milestone,assignees

# Board status per item.
gh project item-list 1 --owner mulino-coreano --limit 200 --format json
```

Group the issues by milestone and read out, per Phase: what is open, what is
in flight, what carries `blocked`, and what is unresolved. Two things are easy
to miss and matter most, so check for them every time:

- **Issues with no milestone** — a goal outside every Phase, which the doctrine
  below says is not yet a goal.
- **Open issues absent from the board** — invisible to every session, because
  there is no auto-add workflow.

Run these before answering any question about project state. Never describe
the project's direction from memory or from `docs/` alone — those lag the
board, sometimes by a whole Phase.

## 2. The doctrine you are working inside

From `docs/06_labels.md`, which outranks this file:

- **마일스톤 = Phase.** The only goal unit. Do not invent a Phase field.
- **이슈 = 목표 1개.** A goal that lives only in a doc is untracked.
- **Status is automation's.** Never set it by hand.
- **How you close carries meaning.** `completed` = actually finished,
  `not planned` = abandoned. Never close unfinished work as `not planned`.
- **`blocked`** needs a comment saying what blocks it.
- **`duplicate`** needs a comment naming the original, then removal from the board.

## 3. Adding a goal

Ask enough to write a real issue — vague goals produce vague work:

- What changes when this is done, stated so someone else could check it?
- Which Phase does it belong to? A goal with no milestone is not a goal yet.
- Is this one goal, or several wearing a trenchcoat?
- Does it already exist? Check before creating.

Then create it from the matching template in `.github/ISSUE_TEMPLATE/`
(`feature` / `bug` / `qc` / `research`), Korean body, with the milestone and
labels set. **Add it to the board** — there is no auto-add workflow, so an
issue that is not added stays invisible to every session:

```bash
gh project item-add 1 --owner mulino-coreano --url <issue url>
```

Set `Scenario` and `Layer` — they classify the goal. Leave `Work Stage` and
`Verification Gate` to a human: those describe how far the work really got,
and an agent asserting its own verification state is how a board starts lying.

## 4. Adjusting a goal

Re-milestone when a Phase's shape changes. Re-prioritize when the critical
path moves. Split when an issue's 상세 작업 내용 has grown into two goals —
create the second issue, link both, and say in a comment why it split.

Mark `blocked` with the reason as a comment. When the block clears, remove
the label and say what cleared it.

## 5. Closing a goal

Closing is a claim about reality. `completed` means the 완료 조건 were met —
check them, don't assume. `not planned` means the project decided against it;
say why in a comment. Work that stalled is neither: it stays open, `blocked`,
with a reason.

Never close an issue that still has an open PR against it — the merge does it.

## Never

- Hand-edit the board's Status field.
- Create a goal without a milestone, or leave it off the board.
- Close an issue as `completed` without reading its 완료 조건.
- Restructure Phases, or close a milestone, without the human saying so.
- Treat your own opinion about priority as a decision. You propose; they decide.
