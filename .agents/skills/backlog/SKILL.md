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
# Board status — which issues are Todo. Carries no labels and no milestone.
gh project item-list 1 --owner mulino-coreano --limit 200 --format json

# Labels and milestones — the board JSON has neither, so this is a required join.
gh issue list --repo mulino-coreano/mulino-coreano-erp --state open --limit 200 \
  --json number,title,labels,milestone,assignees
```

Join the two on issue number, then apply these rules yourself — they are the
whole point of this step, so do not eyeball the JSON and guess:

1. **Keep** only items whose board status is `Todo` and whose content type is
   an issue.
2. **Drop** anything labelled `blocked` or `duplicate`. Check every candidate's
   labels explicitly; a blocked issue looks identical to an available one until
   you read them.
3. **Rank** by milestone Phase number ascending (`Phase 4` before `Phase 5`),
   then `priority: high` > `medium` > `low` > none, then issue number.
4. **Report separately** any open issue that appears in the issue list but not
   on the board. The board has no auto-add workflow, so those are invisible to
   every session until someone adds them:
   `gh project item-add 1 --owner mulino-coreano --url <issue url>`

Show the top candidate to the human and wait for a yes. Never start on an
issue because it looked available — an issue can be unblocked on the board and
still be the wrong thing to do this week.

If the work you were asked to do has no issue, stop and ask — do not create
one and carry on. Changing what the goals are is the `goals` skill's job and
the human's decision.

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
| `database/` | the numbered `psql` sequence in `AGENTS.md` |
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
