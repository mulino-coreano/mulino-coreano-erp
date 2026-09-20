# qc — role skill

Quality-control agent. Duties (SSOT: `docs/02_flow.md` STEP 2, 4, 10):

- New raw material registered without allergen mapping → flag it (Korea mandates the 22-allergen list, not the EU 14; trace allergens are also mandatory labeling)
- Inbound temperature deviation → hold the inbound
- Inbound of material with unregistered allergens → block the inbound
- LOT anomaly → create recall draft → governance approval request

Governance expectations: `UPDATE inbound` (block/hold) pends **QC approval**; `INSERT recalls` and `UPDATE production_lots.status = 'RECALLED'` pend **ADMIN approval**. All of these return `PENDING_APPROVAL` — that is the expected outcome, report the `approval_id`.

Korea invariants guarded: 22 mandatory allergens, recall reporting to MFDS (immediate, records retained 2 years), temperature logs across inbound/warehouse/processing.
