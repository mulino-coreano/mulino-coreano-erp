---
name: backlog
description: Use when taking development work for this repository from GitHub — picking the next issue off the Mulino Coreano project board, claiming it, branching, verifying, and opening the Korean-template PR. This is for building the repo itself. ERP domain requests (stock, LOT trace, PO drafts, recalls) go to the orchestrator skill instead.
---

# Backlog

## Mission

Turn one board item into a review-ready pull request. One issue at a time.
You open the PR; a human merges it. Those two facts do not bend.

The board is [Mulino Coreano — ERP & Agent Governance](https://github.com/orgs/mulino-coreano/projects/1).
Its operating rules are in `docs/06_labels.md` and they outrank anything here.

## 1. Select

```bash
.agents/skills/backlog/next-issue.sh
```

Open `Todo` issues, `blocked` and `duplicate` dropped, ranked by milestone
phase then priority. The arrow marks the pick.

Show the pick to the human and wait for a yes. Never start on an issue
because it looked available — an issue can be unblocked on the board and
still be the wrong thing to do this week.

## 2. Claim

```bash
gh issue edit <N> --add-assignee @me --add-label "in progress"
```

Do not touch the board's Status field. Automation derives it from issue and
PR state; hand-editing it makes the board lie about what is real.

## 3. Branch

```bash
git switch main && git pull --ff-only
git switch -c <type>/<slug>
```

`<type>` from the issue's category label, and it is also the commit prefix:

| Label | Prefix |
|---|---|
| `feature` | `feat` |
| `bug` | `fix` |
| `docs` | `docs` |
| `qc`, `chore` | `chore` |

## 4. Work

The issue's **상세 작업 내용** checkboxes are the task list. The **완료 조건
(Definition of Done)** is the acceptance gate — read it before writing code,
not after.

If the issue turns out to be wrong, ambiguous, or larger than it reads, stop
and say so in an issue comment. Do not silently widen scope, and do not
quietly deliver less than the issue asks for.

## 5. Verify

Run the checks that match what you touched, and paste the real output:

| Touched | Run |
|---|---|
| `backend/` | `cd backend && ./gradlew test` |
| `database/` | the numbered `psql` sequence in `CLAUDE.md` |
| docs only | no command — say so plainly |

There is no CI on this repository yet. Local output is the only evidence a
reviewer gets, so a completion claim without pasted output is a false claim.
When CI exists, defer to it and drop this step.

## 6. Pull request

Korean title, Korean body, `.github/pull_request_template.md` structure exactly
— all four sections, all three checklist items, none renamed or dropped.

```bash
gh pr create --title "<prefix>(<scope>): <한국어 제목>" --body-file <draft> \
  --label "<issue labels>"
```

The body ends with `Closes #<N>` so the merge closes the issue and the board
moves itself. Tick a checklist box only if you actually did it.

## 7. Stop

Report the branch, the PR URL, and the verification output. Then hand back.

## Never

- Merge your own PR, push to `main`, or force-push anything.
- Hand-edit the board's Status field.
- Close an issue directly — `Closes #<N>` in a merged PR does that.
- Commit `application-local.yml`, `.env`, `*.key`, or `*.pem`.
- Tick a template checklist box for work you did not do.

## Board fields you read but never write

`Scenario`, `Work Stage`, `Verification Gate`, `Layer` — context for
understanding where an issue sits in the demo narrative. The selection script
prints them. Writing them is a human's call.
