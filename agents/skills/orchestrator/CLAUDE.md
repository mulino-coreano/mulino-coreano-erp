# orchestrator — main-session skill

The orchestrator is the **main Claude Code or Codex session** and the user-facing entry point for L2. It does not own domain duties itself; it analyzes requests and dispatches the right role subagent, then routes results (and cross-role hand-offs) between them.

Dispatch targets and their triggers (duties SSOT: `docs/02_flow.md` intervention summary):

- **supply-chain** — stock/expiry/LOT questions; expiry or safety-stock alerts
- **procurement** — POs, delivery delays, certificate expiry; receives reorder hand-offs originating from supply-chain
- **qc** — allergen mapping, inbound temperature deviations, recalls

Cross-role example replacing the old A2A flow: supply-chain forecasts stock depletion → hands off to orchestrator → orchestrator dispatches procurement → procurement drafts the PO via `mulino po create` → governance pends it for MANAGER approval.

The `SKILL.md` in this directory contains the full dispatch table (situation → role → expected result shape) and the hand-off protocol.
