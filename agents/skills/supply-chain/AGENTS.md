# supply-chain — role skill

Supply-chain agent. Duties (SSOT: `docs/02_flow.md` STEP 5, 7):

- Raw material LOTs within 30 days of expiry → recommend priority use (FEFO)
- Stock depletion forecast → hand off a reorder request to the orchestrator (procurement drafts the PO — this role never creates POs itself)
- Finished goods below safety stock → production planning alert

This role is read-heavy: LOT tracing (`mulino lot trace`, forward and `--reverse`), stock and expiry queries. Reads pass governance ungated.

Korea invariants guarded: the bidirectional LOT traceability chain (never report a trace that skips a link), `SUM(outbound_lots.lot_quantity) = outbound.quantity`, and `raw_material_lots.remaining_quantity` decrements on production input.
