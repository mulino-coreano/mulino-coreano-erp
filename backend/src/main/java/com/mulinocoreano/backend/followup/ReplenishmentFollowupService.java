package com.mulinocoreano.backend.followup;

import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_FOLLOWUPS;

import com.mulinocoreano.backend.procurement.PurchaseVerificationService;

import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class ReplenishmentFollowupService {
    private final ReplenishmentFollowupRepository repository;
    private final PurchaseVerificationService verification;
    private final Clock clock;

    public ReplenishmentFollowupService(
            ReplenishmentFollowupRepository repository,
            PurchaseVerificationService verification,
            @Qualifier("planningClock") Clock clock) {
        this.repository = repository;
        this.verification = verification;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureForVerifiedCompletion(long caseId, long sourceWorkItemId) {
        var source = repository.lockSource(caseId, sourceWorkItemId);
        var existing = repository.existing(source.planId());
        if (existing != null) {
            if (existing.get(REPLENISHMENT_FOLLOWUPS.SOURCE_WORK_ITEM_ID) != sourceWorkItemId)
                throw new IllegalStateException("FOLLOWUP_SOURCE_CONFLICT");
            return;
        }
        if (!verification.verified(caseId, sourceWorkItemId))
            throw new IllegalStateException("FOLLOWUP_PURCHASE_NOT_VERIFIED");
        evaluate(repository.create(source), OffsetDateTime.now(clock));
    }

    @Transactional
    public List<Long> sweepDue() {
        var now = OffsetDateTime.now(clock);
        var events = new ArrayList<Long>();
        for (long work : repository.candidates(now)) {
            var row = repository.lockCandidate(work);
            if (row == null
                    || (row.get(REPLENISHMENT_FOLLOWUPS.DUE_AT) != null
                            && row.get(REPLENISHMENT_FOLLOWUPS.DUE_AT).isAfter(now))) continue;
            var id = evaluate(row, now);
            if (id != null) events.add(id);
        }
        return List.copyOf(events);
    }

    private Long evaluate(Record f, OffsetDateTime now) {
        boolean production = false;
        for (var row :
                repository
                        .planResult(f.get(REPLENISHMENT_FOLLOWUPS.REPLENISHMENT_PLAN_ID))
                        .path("requirements")
                        .path("production"))
            if (row.path("productionQuantity").decimalValue().signum() > 0) production = true;
        var observation = new LinkedHashMap<String, Object>();
        observation.put("sourceOutcome", f.get(REPLENISHMENT_FOLLOWUPS.SOURCE_OUTCOME));
        observation.put("productionRequired", production);
        observation.put(
                "remainingObligation", production ? "PRODUCTION_AND_STOCK_REVIEW" : "STOCK_REVIEW");
        OffsetDateTime due = null;
        boolean attention = false;
        String status;
        var lines = new ArrayList<Map<String, Object>>();
        if (f.get(REPLENISHMENT_FOLLOWUPS.PURCHASE_APPLICATION_ID) != null) {
            boolean all = true, late = false, undated = false;
            for (var line :
                    repository.receipts(f.get(REPLENISHMENT_FOLLOWUPS.PURCHASE_APPLICATION_ID))) {
                var summary = assess(line, now);
                lines.add(summary.observation());
                if (!summary.fulfilled()) {
                    all = false;
                    if (summary.dueAt() == null) undated = true;
                    else {
                        if (due == null || summary.dueAt().isBefore(due)) due = summary.dueAt();
                        if (!summary.dueAt().isAfter(now)) late = true;
                    }
                }
            }
            if (lines.isEmpty()) {
                all = false;
                undated = true;
            }
            status =
                    all
                            ? (production ? "PRODUCTION_REVIEW_REQUIRED" : "STOCK_REVIEW_REQUIRED")
                            : (late || undated ? "RECEIPT_REVIEW_REQUIRED" : "AWAITING_RECEIPT");
            attention = all || late || undated;
        } else {
            status = production ? "PRODUCTION_REVIEW_REQUIRED" : "STOCK_REVIEW_REQUIRED";
            attention = true;
        }
        observation.put("status", status);
        observation.put("dueAt", due == null ? null : due.toString());
        observation.put("lines", lines);
        return repository.observe(f, status, due, observation, attention, now);
    }

    static OffsetDateTime checkAt(LocalDate date) {
        return date == null
                ? null
                : date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }

    static Assessment assess(ReplenishmentFollowupRepository.ReceiptLine line, OffsetDateTime now) {
        BigDecimal received = BigDecimal.ZERO;
        boolean invalid = false;
        var receipts = new ArrayList<Map<String, Object>>();
        for (var r : line.receipts().values()) {
            BigDecimal lots =
                    r.lots().stream()
                            .map(ReplenishmentFollowupRepository.Lot::quantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean valid =
                    r.identityMatches()
                            && "RELEASED".equals(r.status())
                            && !r.lots().isEmpty()
                            && r.lots().stream()
                                    .allMatch(ReplenishmentFollowupRepository.Lot::identityMatches)
                            && lots.compareTo(r.quantity()) == 0;
            if (valid) received = received.add(r.quantity());
            else invalid = true;
            receipts.add(
                    Map.of(
                            "inboundId",
                            r.id(),
                            "quantity",
                            r.quantity(),
                            "status",
                            r.status(),
                            "identityMatches",
                            r.identityMatches(),
                            "lots",
                            r.lots(),
                            "usable",
                            valid));
        }
        boolean fulfilled = !invalid && received.compareTo(line.ordered()) == 0;
        OffsetDateTime due = checkAt(line.date());
        String status =
                fulfilled
                        ? "RECEIVED"
                        : invalid
                                ? "RECEIPT_MISMATCH_OR_HOLD"
                                : due == null
                                        ? "DELIVERY_DATE_MISSING"
                                        : due.isAfter(now)
                                                ? "NOT_DUE"
                                                : received.signum() > 0
                                                        ? "PARTIAL_RECEIPT"
                                                        : "MISSING_RECEIPT";
        var observation = new LinkedHashMap<String, Object>();
        observation.put("purchaseOrderItemId", line.itemId());
        observation.put("orderedQuantity", line.ordered());
        observation.put("usableQuantity", received);
        observation.put(
                "expectedDeliveryDate", line.date() == null ? null : line.date().toString());
        observation.put("status", status);
        observation.put("receipts", receipts);
        return new Assessment(fulfilled, due, observation);
    }

    record Assessment(boolean fulfilled, OffsetDateTime dueAt, Map<String, Object> observation) {}
}
