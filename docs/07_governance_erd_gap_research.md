# Governance/ERD Gap Research

> Purpose: identify what remains outside the current 24-table DDL and define the smallest schema additions needed before Phase 5 (Governance Engine) implementation. This document is a design proposal; it does not change the current schema.

## Research Result

The current ERD accurately models the implemented traceability data backbone, but not the full target solution. Three groups remain unimplemented:

1. **Governance persistence**: approval requests, decisions, and action audit logs exist only as narrative in `docs/02_flow.md`.
2. **Operational quality states**: inbound hold/block, temperature alert status, and sensor references are discussed conceptually but have no columns or tables.
3. **Regulatory evidence**: recall reporting, MFDS traceability transmission, allergen legal category, and record-retention evidence are not represented as auditable data.

## Current Schema Coverage and Gaps

| Requirement | Current implementation | Remaining gap |
|---|---|---|
| Bidirectional LOT trace | Full supplier → inbound → raw-material LOT → production → finished LOT → outbound → customer chain | No transmission/retention evidence for external traceability reporting |
| Korean allergen labeling | `allergens` master and `raw_material_allergens` mapping | No legal category/group field; no data lineage distinguishing the 19 legal groups from operational sub-items |
| Supplier certificate control | `supplier_certifications` with type, dates, and file | No inbound enforcement result/reason |
| Inbound quality control | Quantity, dates, supplier/material/warehouse, temperature measurements | No hold/block state, decision actor/time, or required action |
| Temperature monitoring | Numeric readings at inbound/warehouse | No alert status, evaluated rule version, or sensor identifier |
| Governance matrix | Narrative approval matrix and `users.role` | No persisted approval request, decision, or action audit log |
| Recall workflow | `recalls` with target lot, reason/date/status | No MFDS report evidence, submission timestamp/result, or decision trail |
| Record retention | Timestamps and FK defaults prevent trace-chain deletion | No retention deadline derived from product expiry, transmission deadline, or legal-hold flag |

## Proposed Minimum Schema

### 1. Governance action and approval

```sql
CREATE TABLE governance_actions (
    governance_action_id BIGSERIAL PRIMARY KEY,
    requested_by INT NOT NULL REFERENCES users(user_id),
    action_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id INT NOT NULL,
    payload JSONB NOT NULL,
    status governance_action_status NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE governance_decisions (
    governance_decision_id BIGSERIAL PRIMARY KEY,
    governance_action_id BIGINT NOT NULL REFERENCES governance_actions(governance_action_id),
    decided_by INT NOT NULL REFERENCES users(user_id),
    decision governance_decision_type NOT NULL,
    reason TEXT NOT NULL,
    decided_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (governance_action_id, decided_by, decided_at)
);

CREATE TABLE governance_audit_logs (
    governance_audit_log_id BIGSERIAL PRIMARY KEY,
    governance_action_id BIGINT REFERENCES governance_actions(governance_action_id),
    actor_id INT REFERENCES users(user_id),
    event_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id INT NOT NULL,
    before_state JSONB,
    after_state JSONB,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Design rules:

- `payload` is the proposed action snapshot, not a substitute for normalized ERP tables.
- `governance_actions.status` must move to `APPROVED`, `BLOCKED`, or `EXPIRED` only through a persisted decision/audit event.
- Every approved or blocked write requires an immutable `governance_audit_logs` row.
- Read actions remain pass-through and do not create governance action requests.

### 2. Inbound quality decision

```sql
CREATE TYPE inbound_status AS ENUM('RELEASED', 'HOLD', 'BLOCKED');

ALTER TABLE inbound
    ADD COLUMN status inbound_status NOT NULL DEFAULT 'HOLD',
    ADD COLUMN status_reason TEXT,
    ADD COLUMN status_decided_by INT REFERENCES users(user_id),
    ADD COLUMN status_decided_at TIMESTAMP,
    ADD CONSTRAINT ck_inbound_status_metadata
    CHECK (
      (status = 'HOLD' AND status_reason IS NULL)
      OR (status <> 'HOLD' AND status_reason IS NOT NULL
          AND status_decided_by IS NOT NULL
          AND status_decided_at IS NOT NULL)
    );
```

Use `HOLD` as the initial state so unreviewed inbound stock is never implicitly releasable. This directly supports the QC approval matrix without trying to encode that workflow in the temperature log.

### 3. Alert evaluation

```sql
CREATE TABLE alert_rules (
    alert_rule_id SERIAL PRIMARY KEY,
    resource_type VARCHAR(50) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    threshold JSONB NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMP
);

CREATE TABLE alert_events (
    alert_event_id BIGSERIAL PRIMARY KEY,
    alert_rule_id INT REFERENCES alert_rules(alert_rule_id),
    inbound_id INT REFERENCES inbound(inbound_id),
    warehouse_id INT REFERENCES warehouses(warehouse_id),
    sensor_id VARCHAR(100),
    alert_type VARCHAR(100) NOT NULL,
    observed_value JSONB NOT NULL,
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    CHECK ((inbound_id IS NULL) <> (warehouse_id IS NULL))
);
```

`sensor_id` belongs on the alert event, not necessarily on every temperature row: an imported/manual reading can still raise an alert without a stable sensor identifier. Keep raw temperature rows immutable and put rule version and resolution state in `alert_events`.

### 4. Regulatory evidence

```sql
CREATE TABLE regulatory_submissions (
    regulatory_submission_id BIGSERIAL PRIMARY KEY,
    submission_type regulatory_submission_type NOT NULL,
    recall_id INT REFERENCES recalls(recall_id),
    submitted_at TIMESTAMP,
    confirmation_number VARCHAR(100),
    result regulatory_submission_result,
    due_at TIMESTAMP NOT NULL,
    evidence_url VARCHAR(255),
    CHECK (
      (submission_type = 'RECALL_REPORT' AND recall_id IS NOT NULL)
      OR (submission_type = 'TRACEABILITY_TRANSMISSION' AND recall_id IS NULL)
    )
);
```

Store the regulatory obligation and its evidence separately from business operations. This enables “report immediately” and “transmit inbound/outbound traceability within five days” compliance checks without mixing external submission state into `recalls`, `inbound`, or `outbound`.

## Required Supporting Changes

- Add enums:
  - `governance_action_status`: `PENDING`, `APPROVED`, `BLOCKED`, `EXPIRED`
  - `governance_decision_type`: `APPROVE`, `BLOCK`, `CANCEL`
  - `regulatory_submission_type`: `RECALL_REPORT`, `TRACEABILITY_TRANSMISSION`
  - `regulatory_submission_result`: `PENDING`, `ACCEPTED`, `REJECTED`
- Add `allergens.legal_category` (or an equivalent legal-group parent) so the schema can distinguish:
  - 19 Korean legal labeling groups, including one shellfish group, from
  - Operational rows/sub-items such as oyster, abalone, and mussel.
- Add `products.traceability_registration_number` if `trace_code` is not contractually defined as the official food-traceability registration number. Rename or document it before API implementation.
- Add a scheduled calculation or view for `retention_due_at >= product_consumption_expiry + 2 years`.
- Add composite uniqueness and checks, independently of governance:
  - `UNIQUE (raw_material_id, allergen_id)` on `raw_material_allergens`
  - `UNIQUE (product_id, warehouse_id)` on `stock`
  - `CHECK (issue_date <= expiry_date)` on supplier certificates
  - positive quantity checks on purchasing, inventory, production, and outbound columns
  - `CHECK (NOT (lot_id IS NULL AND raw_lot_id IS NULL))` on `recalls`
  - a trigger-level or application-level check that outbound product and order-item product agree

## Governance Approval Matrix Mapping

| Matrix rule | Request routing | Persisted evidence |
|---|---|---|
| `INSERT purchase_orders` | MANAGER approval | `governance_actions` + decision + audit log |
| `UPDATE inbound` hold/block | QC approval | `governance_actions` + decision + audit log + inbound status metadata |
| `INSERT recalls` | ADMIN approval | `governance_actions` + decision + audit log + regulatory submission task |
| `UPDATE production_lots.status = 'RECALLED'` | ADMIN approval | `governance_actions` + decision + audit log |

## Rollout Sequence

1. Add integrity constraints and the allergen legal-group model.
2. Add `inbound.status` and quality-decision metadata.
3. Add governance actions/decisions/audit logs; make all agent writes pass through them.
4. Add alert rules/events and connect them to QC workflows.
5. Add regulatory submission evidence and retention scheduling.
6. Update `docs/02_flow.md` and the ERD only after the DDL is changed.

## Sources

- 식품 등의 이력추적관리기준, MFDS Notice 2026-39, effective 2026-05-15:
  - Requires electronic record retention for at least **two years after the product’s consumption/expiry date**.
  - Requires traceability information transmission within **five days of inbound/outbound**, excluding Saturdays and public holidays.
  - [국가법령정보센터](https://www.law.go.kr/LSW//admRulInfoP.do?admRulSeq=2100000279276&chrClsCd=010201)
- MFDS notice 2016-734 proposal for children’s preferred-food allergen labeling:
  - Lists peach, tomato, sulfites, walnut, chicken, beef, squid, and **shellfish including oyster, abalone, and mussel**.
  - This supports 19 legal item groups, with shellfish sub-items maintained operationally rather than counted as separate legal groups.
  - [MFDS administrative notice](https://www.mfds.go.kr/brd/m_209/view.do?seq=34655&page=68)
