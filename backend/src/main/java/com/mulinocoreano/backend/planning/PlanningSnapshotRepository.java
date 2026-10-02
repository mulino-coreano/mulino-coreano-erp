package com.mulinocoreano.backend.planning;

import static com.mulinocoreano.backend.generated.Tables.BOM_COMPONENTS;
import static com.mulinocoreano.backend.generated.Tables.BOM_VERSIONS;
import static com.mulinocoreano.backend.generated.Tables.INBOUND;
import static com.mulinocoreano.backend.generated.Tables.MEASUREMENT_UNITS;
import static com.mulinocoreano.backend.generated.Tables.ORDERS;
import static com.mulinocoreano.backend.generated.Tables.ORDER_ITEMS;
import static com.mulinocoreano.backend.generated.Tables.OUTBOUND;
import static com.mulinocoreano.backend.generated.Tables.OUTBOUND_LOTS;
import static com.mulinocoreano.backend.generated.Tables.PLANNING_POLICIES;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTION_INGREDIENTS;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTION_LOTS;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTION_PRODUCT_INPUTS;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTION_RECORDS;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDERS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDER_ITEMS;
import static com.mulinocoreano.backend.generated.Tables.RAW_MATERIALS;
import static com.mulinocoreano.backend.generated.Tables.RAW_MATERIAL_LOTS;
import static com.mulinocoreano.backend.generated.Tables.STOCK;
import static com.mulinocoreano.backend.generated.Tables.SUPPLIERS;
import static com.mulinocoreano.backend.generated.Tables.SUPPLIER_CERTIFICATIONS;
import static com.mulinocoreano.backend.generated.Tables.SUPPLIER_MATERIAL_TERMS;
import static com.mulinocoreano.backend.generated.Tables.WAREHOUSES;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.select;

import com.mulinocoreano.backend.generated.enums.OrderStatus;

import org.jooq.DSLContext;
import org.jooq.EnumType;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Reads one ERP snapshot. A calling transaction must also use REPEATABLE_READ or SERIALIZABLE. */
@Repository
public class PlanningSnapshotRepository {
    private final DSLContext dsl;

    public PlanningSnapshotRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ,
            noRollbackFor = IllegalArgumentException.class)
    public Snapshot load(long warehouseId, List<Long> productIds, LocalDate asOf, int horizonDays)
            throws IllegalArgumentException {
        require(
                warehouseId > 0
                        && productIds != null
                        && !productIds.isEmpty()
                        && asOf != null
                        && horizonDays >= 1
                        && horizonDays <= 90,
                "INVALID_SNAPSHOT_REQUEST");
        require(productIds.stream().allMatch(id -> id != null && id > 0), "INVALID_PRODUCT_ID");
        String isolation = dsl.fetchOne("SHOW transaction_isolation").get(0, String.class);
        require(
                Set.of("repeatable read", "serializable").contains(isolation),
                "INCOHERENT_SNAPSHOT_TRANSACTION");
        var roots = new TreeSet<>(productIds);
        var facts = new TreeMap<String, SourceFact>();
        require(
                rows(
                                        facts,
                                        "warehouses",
                                        "warehouse_id",
                                        dsl.select(
                                                        WAREHOUSES.WAREHOUSE_ID,
                                                        WAREHOUSES.NAME,
                                                        WAREHOUSES.PLANT_ID)
                                                .from(WAREHOUSES)
                                                .where(WAREHOUSES.WAREHOUSE_ID.eq(warehouseId)))
                                .size()
                        == 1,
                "UNKNOWN_WAREHOUSE: " + warehouseId);
        var policies =
                rows(
                        facts,
                        "planning_policies",
                        "warehouse_id",
                        dsl.select(
                                        PLANNING_POLICIES.WAREHOUSE_ID,
                                        PLANNING_POLICIES.HISTORY_START_DATE,
                                        PLANNING_POLICIES.HORIZON_DAYS,
                                        PLANNING_POLICIES.SAFETY_STOCK_DAYS)
                                .from(PLANNING_POLICIES)
                                .where(PLANNING_POLICIES.WAREHOUSE_ID.eq(warehouseId)));
        require(policies.size() == 1, "MISSING_PLANNING_POLICY: " + warehouseId);
        Map<String, Object> policy = policies.getFirst();
        var catalog =
                rows(
                        facts,
                        "measurement_units",
                        "code",
                        dsl.select(
                                        MEASUREMENT_UNITS.CODE,
                                        MEASUREMENT_UNITS.DIMENSION,
                                        MEASUREMENT_UNITS.TO_CANONICAL_FACTOR)
                                .from(MEASUREMENT_UNITS));
        var units = new HashSet<String>();
        catalog.forEach(row -> units.add(str(row, "code")));

        var products = new TreeMap<Long, Map<String, Object>>();
        var materials = new TreeMap<Long, Map<String, Object>>();
        var boms = new ArrayList<BomPlanner.Bom>();
        var rawIds = new TreeSet<Long>();
        var pending = new TreeSet<>(roots);
        LocalDate end = asOf.plusDays(horizonDays - 1L);
        while (!pending.isEmpty()) {
            var current = new TreeSet<>(pending);
            pending.clear();
            var productRows =
                    rows(
                            facts,
                            "products",
                            "product_id",
                            dsl.select(
                                            PRODUCTS.PRODUCT_ID,
                                            PRODUCTS.SKU,
                                            PRODUCTS.NAME,
                                            PRODUCTS.UNIT,
                                            PRODUCTS.EXPIRY_DAYS,
                                            PRODUCTS.PRODUCT_TYPE,
                                            PRODUCTS.IS_ACTIVE)
                                    .from(PRODUCTS)
                                    .where(PRODUCTS.PRODUCT_ID.in(current)));
            require(productRows.size() == current.size(), "UNKNOWN_PRODUCT");
            for (var row : productRows) {
                long id = id(row, "product_id");
                require(bool(row, "is_active"), "INACTIVE_PRODUCT: " + id);
                require(
                        !roots.contains(id) || str(row, "product_type").equals("FINISHED_GOODS"),
                        "FINISHED_GOODS_REQUIRED: " + id);
                validateUnit(row, units, "products:" + id);
                products.put(id, row);
            }
            var versions =
                    rows(
                            facts,
                            "bom_versions",
                            "bom_version_id",
                            dsl.select(
                                            BOM_VERSIONS.BOM_VERSION_ID,
                                            BOM_VERSIONS.PRODUCT_ID,
                                            BOM_VERSIONS.VERSION,
                                            BOM_VERSIONS.BATCH_OUTPUT_QUANTITY,
                                            BOM_VERSIONS.PRODUCTION_LEAD_DAYS,
                                            BOM_VERSIONS.VALID_FROM,
                                            BOM_VERSIONS.VALID_TO,
                                            BOM_VERSIONS.IS_ACTIVE)
                                    .from(BOM_VERSIONS)
                                    .where(
                                            BOM_VERSIONS
                                                    .PRODUCT_ID
                                                    .in(current)
                                                    .and(BOM_VERSIONS.IS_ACTIVE.isTrue())
                                                    .and(BOM_VERSIONS.VALID_FROM.le(end))
                                                    .and(
                                                            BOM_VERSIONS
                                                                    .VALID_TO
                                                                    .isNull()
                                                                    .or(
                                                                            BOM_VERSIONS.VALID_TO
                                                                                    .ge(asOf)))));
            if (versions.isEmpty()) continue;
            var components =
                    rows(
                            facts,
                            "bom_components",
                            "bom_component_id",
                            dsl.select(
                                            BOM_COMPONENTS.BOM_COMPONENT_ID,
                                            BOM_COMPONENTS.BOM_VERSION_ID,
                                            BOM_COMPONENTS.CHILD_PRODUCT_ID,
                                            BOM_COMPONENTS.RAW_MATERIAL_ID,
                                            BOM_COMPONENTS.QUANTITY_PER_BATCH)
                                    .from(BOM_COMPONENTS)
                                    .where(
                                            BOM_COMPONENTS.BOM_VERSION_ID.in(
                                                    versions.stream()
                                                            .map(row -> id(row, "bom_version_id"))
                                                            .toList())));
            for (var row : components) {
                if (row.get("child_product_id") != null) {
                    long child = id(row, "child_product_id");
                    if (!products.containsKey(child)) pending.add(child);
                } else rawIds.add(id(row, "raw_material_id"));
            }
            // The component unit is resolved after all reachable master rows have been loaded.
        }
        if (!rawIds.isEmpty()) {
            for (var row :
                    rows(
                            facts,
                            "raw_materials",
                            "raw_material_id",
                            dsl.select(
                                            RAW_MATERIALS.RAW_MATERIAL_ID,
                                            RAW_MATERIALS.NAME,
                                            RAW_MATERIALS.UNIT,
                                            RAW_MATERIALS.MATERIAL_TYPE,
                                            RAW_MATERIALS.SUPPLIER_ID)
                                    .from(RAW_MATERIALS)
                                    .where(RAW_MATERIALS.RAW_MATERIAL_ID.in(rawIds)))) {
                validateUnit(row, units, "raw_materials:" + id(row, "raw_material_id"));
                materials.put(id(row, "raw_material_id"), row);
            }
            require(materials.size() == rawIds.size(), "UNKNOWN_RAW_MATERIAL");
        }
        // Facts are JSON-compatible, so dates are read from their canonical ISO strings here.
        for (var fact : facts.values()) {
            if (!fact.sourceRef().startsWith("bom_versions:")) continue;
            var version = fact.values();
            long versionId = id(version, "bom_version_id");
            var componentInputs =
                    facts.values().stream()
                            .filter(f -> f.sourceRef().startsWith("bom_components:"))
                            .map(SourceFact::values)
                            .filter(row -> id(row, "bom_version_id") == versionId)
                            .sorted(Comparator.comparingLong(row -> id(row, "bom_component_id")))
                            .map(
                                    row ->
                                            new BomPlanner.Component(
                                                    row.get("child_product_id") != null
                                                            ? productItem(
                                                                    products.get(
                                                                            id(
                                                                                    row,
                                                                                    "child_product_id")))
                                                            : materialItem(
                                                                    materials.get(
                                                                            id(
                                                                                    row,
                                                                                    "raw_material_id"))),
                                                    decimal(row, "quantity_per_batch")))
                            .toList();
            var product = products.get(id(version, "product_id"));
            boms.add(
                    new BomPlanner.Bom(
                            fact.sourceRef(),
                            productItem(product),
                            decimal(version, "batch_output_quantity"),
                            integer(version, "production_lead_days"),
                            integer(product, "expiry_days"),
                            date(version, "valid_from"),
                            date(version, "valid_to"),
                            componentInputs));
        }
        boms.sort(
                Comparator.comparingLong((BomPlanner.Bom b) -> b.product().id())
                        .thenComparing(BomPlanner.Bom::validFrom)
                        .thenComparing(BomPlanner.Bom::ref));
        var outbound = loadOutbound(facts, products.keySet());
        var supply = new ArrayList<BomPlanner.StockLot>();
        loadProductSupply(facts, warehouseId, products, outbound, supply);
        if (!rawIds.isEmpty()) {
            loadPurchases(facts, warehouseId, rawIds, materials, asOf, end, supply);
            loadRawSupply(facts, warehouseId, materials, supply);
        }
        var productInputs = loadDemand(facts, roots, products, outbound, asOf);
        var supplierTerms = new TreeMap<Long, List<SupplierSelectionService.SupplierTerm>>();
        var certificates = new ArrayList<SupplierSelectionService.Certificate>();
        if (!rawIds.isEmpty()) loadSuppliers(facts, materials, supplierTerms, certificates);
        supply.sort(Comparator.comparing(BomPlanner.StockLot::sourceRef));
        return new Snapshot(
                warehouseId,
                asOf,
                horizonDays,
                integer(policy, "safety_stock_days"),
                date(policy, "history_start_date"),
                productInputs,
                boms,
                supply,
                supplierTerms,
                certificates,
                new ArrayList<>(facts.values()));
    }

    private List<Map<String, Object>> loadOutbound(
            Map<String, SourceFact> facts, Set<Long> products) {
        var shipments =
                rows(
                        facts,
                        "outbound",
                        "outbound_id",
                        dsl.select(
                                        OUTBOUND.OUTBOUND_ID,
                                        OUTBOUND.PRODUCT_ID,
                                        OUTBOUND.WAREHOUSE_ID,
                                        OUTBOUND.ORDER_ID,
                                        OUTBOUND.QUANTITY,
                                        OUTBOUND.OUTBOUND_DATE)
                                .from(OUTBOUND)
                                .where(
                                        OUTBOUND.PRODUCT_ID
                                                .in(products)
                                                .or(
                                                        OUTBOUND.OUTBOUND_ID.in(
                                                                select(OUTBOUND_LOTS.OUTBOUND_ID)
                                                                        .from(OUTBOUND_LOTS)
                                                                        .join(PRODUCTION_LOTS)
                                                                        .on(
                                                                                PRODUCTION_LOTS
                                                                                        .PRODUCTION_LOT_ID
                                                                                        .eq(
                                                                                                OUTBOUND_LOTS
                                                                                                        .LOT_ID))
                                                                        .where(
                                                                                PRODUCTION_LOTS
                                                                                        .PRODUCT_ID
                                                                                        .in(
                                                                                                products))))));
        if (shipments.isEmpty()) return shipments;
        var allocations =
                rows(
                        facts,
                        "outbound_lots",
                        "outbound_lot_id",
                        dsl.select(
                                        OUTBOUND_LOTS.OUTBOUND_LOT_ID,
                                        OUTBOUND_LOTS.OUTBOUND_ID,
                                        OUTBOUND_LOTS.LOT_ID,
                                        OUTBOUND_LOTS.LOT_QUANTITY,
                                        PRODUCTION_LOTS.PRODUCT_ID.as("lot_product_id"),
                                        PRODUCTION_LOTS.WAREHOUSE_ID.as("lot_warehouse_id"))
                                .from(OUTBOUND_LOTS)
                                .join(PRODUCTION_LOTS)
                                .on(PRODUCTION_LOTS.PRODUCTION_LOT_ID.eq(OUTBOUND_LOTS.LOT_ID))
                                .where(
                                        OUTBOUND_LOTS.OUTBOUND_ID.in(
                                                shipments.stream()
                                                        .map(row -> id(row, "outbound_id"))
                                                        .toList())));
        for (var shipment : shipments) {
            long shipmentId = id(shipment, "outbound_id");
            var selected =
                    allocations.stream()
                            .filter(row -> id(row, "outbound_id") == shipmentId)
                            .toList();
            require(
                    sum(selected, "lot_quantity").compareTo(decimal(shipment, "quantity")) == 0,
                    "OUTBOUND_LOT_MISMATCH: outbound:" + shipmentId);
            for (var row : selected)
                require(
                        id(row, "lot_product_id") == id(shipment, "product_id")
                                && (row.get("lot_warehouse_id") == null
                                        || id(row, "lot_warehouse_id")
                                                == id(shipment, "warehouse_id")),
                        "OUTBOUND_LOT_IDENTITY_MISMATCH: outbound_lots:"
                                + id(row, "outbound_lot_id"));
        }
        return shipments;
    }

    private void loadProductSupply(
            Map<String, SourceFact> facts,
            long warehouse,
            Map<Long, Map<String, Object>> products,
            List<Map<String, Object>> outbound,
            List<BomPlanner.StockLot> supply) {
        var lots =
                rows(
                        facts,
                        "production_lots",
                        "production_lot_id",
                        dsl.select(
                                        PRODUCTION_LOTS.PRODUCTION_LOT_ID,
                                        PRODUCTION_LOTS.PRODUCT_ID,
                                        PRODUCTION_LOTS.WAREHOUSE_ID,
                                        PRODUCTION_LOTS.LOT_NUMBER,
                                        PRODUCTION_LOTS.QUANTITY,
                                        PRODUCTION_LOTS.PRODUCTION_DATE,
                                        PRODUCTION_LOTS.EXPIRY_DATE,
                                        PRODUCTION_LOTS.STATUS)
                                .from(PRODUCTION_LOTS)
                                .where(PRODUCTION_LOTS.PRODUCT_ID.in(products.keySet())));
        var inputs =
                lots.isEmpty()
                        ? List.<Map<String, Object>>of()
                        : rows(
                                facts,
                                "production_product_inputs",
                                "production_product_input_id",
                                dsl.select(
                                                PRODUCTION_PRODUCT_INPUTS
                                                        .PRODUCTION_PRODUCT_INPUT_ID,
                                                PRODUCTION_PRODUCT_INPUTS.PRODUCTION_RECORD_ID,
                                                PRODUCTION_PRODUCT_INPUTS.SOURCE_PRODUCTION_LOT_ID,
                                                PRODUCTION_PRODUCT_INPUTS.QUANTITY_USED,
                                                PRODUCTION_RECORDS.LOT_ID.as("target_lot_id"),
                                                PRODUCTION_RECORDS.WAREHOUSE_ID.as(
                                                        "target_warehouse_id"))
                                        .from(PRODUCTION_PRODUCT_INPUTS)
                                        .join(PRODUCTION_RECORDS)
                                        .on(
                                                PRODUCTION_RECORDS.PRODUCTION_RECORD_ID.eq(
                                                        PRODUCTION_PRODUCT_INPUTS
                                                                .PRODUCTION_RECORD_ID))
                                        .where(
                                                PRODUCTION_PRODUCT_INPUTS.SOURCE_PRODUCTION_LOT_ID
                                                        .in(
                                                                lots.stream()
                                                                        .map(
                                                                                row ->
                                                                                        id(
                                                                                                row,
                                                                                                "production_lot_id"))
                                                                        .toList())));
        var stocks =
                rows(
                        facts,
                        "stock",
                        "stock_id",
                        dsl.select(
                                        STOCK.STOCK_ID,
                                        STOCK.PRODUCT_ID,
                                        STOCK.WAREHOUSE_ID,
                                        STOCK.QUANTITY)
                                .from(STOCK)
                                .where(
                                        STOCK.WAREHOUSE_ID
                                                .eq(warehouse)
                                                .and(STOCK.PRODUCT_ID.in(products.keySet()))));
        var physical = new HashMap<Long, BigDecimal>();
        for (var lot : lots) {
            long lotId = id(lot, "production_lot_id");
            BigDecimal shipped =
                    facts.values().stream()
                            .filter(f -> f.sourceRef().startsWith("outbound_lots:"))
                            .map(SourceFact::values)
                            .filter(row -> id(row, "lot_id") == lotId)
                            .map(row -> decimal(row, "lot_quantity"))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            var used =
                    inputs.stream()
                            .filter(row -> id(row, "source_production_lot_id") == lotId)
                            .toList();
            BigDecimal residual =
                    decimal(lot, "quantity").subtract(shipped).subtract(sum(used, "quantity_used"));
            require(residual.signum() >= 0, "PRODUCT_LOT_OVERCONSUMED: production_lots:" + lotId);
            require(
                    residual.signum() == 0 || lot.get("warehouse_id") != null,
                    "MISSING_LOT_WAREHOUSE: production_lots:" + lotId);
            if (lot.get("warehouse_id") == null || id(lot, "warehouse_id") != warehouse) continue;
            for (var input : used)
                require(
                        id(input, "target_warehouse_id") == warehouse,
                        "PRODUCT_INPUT_WAREHOUSE_MISMATCH: production_product_inputs:"
                                + id(input, "production_product_input_id"));
            long productId = id(lot, "product_id");
            physical.merge(productId, residual, BigDecimal::add);
            String status = str(lot, "status");
            supply.add(
                    new BomPlanner.StockLot(
                            "production_lots:" + lotId,
                            productItem(products.get(productId)),
                            residual,
                            date(lot, "production_date"),
                            date(lot, "expiry_date"),
                            status.equals("ACTIVE") ? null : "PRODUCT_" + status,
                            false));
        }
        for (long productId : products.keySet()) {
            var matching =
                    stocks.stream().filter(row -> id(row, "product_id") == productId).toList();
            require(
                    matching.size() == 1,
                    "MISSING_STOCK: products:" + productId + ":warehouse:" + warehouse);
            require(
                    decimal(matching.getFirst(), "quantity")
                                    .compareTo(physical.getOrDefault(productId, BigDecimal.ZERO))
                            == 0,
                    "STOCK_LOT_MISMATCH: products:" + productId + ":warehouse:" + warehouse);
        }
    }

    private void loadPurchases(
            Map<String, SourceFact> facts,
            long warehouse,
            Set<Long> rawIds,
            Map<Long, Map<String, Object>> materials,
            LocalDate start,
            LocalDate end,
            List<BomPlanner.StockLot> supply) {
        var items =
                rows(
                        facts,
                        "purchase_order_items",
                        "purchase_order_item_id",
                        dsl.select(
                                        PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ITEM_ID,
                                        PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID,
                                        PURCHASE_ORDER_ITEMS.RAW_MATERIAL_ID,
                                        PURCHASE_ORDER_ITEMS.QUANTITY,
                                        PURCHASE_ORDER_ITEMS.RECEIVED_QUANTITY,
                                        PURCHASE_ORDER_ITEMS.UNIT_PRICE,
                                        PURCHASE_ORDERS.SUPPLIER_ID,
                                        PURCHASE_ORDERS.ORDER_DATE,
                                        coalesce(
                                                        PURCHASE_ORDER_ITEMS.EXPECTED_DELIVERY_DATE,
                                                        PURCHASE_ORDERS.EXPECTED_DELIVERY_DATE)
                                                .as("expected_delivery_date"),
                                        PURCHASE_ORDERS.WAREHOUSE_ID.as("purchase_warehouse_id"),
                                        PURCHASE_ORDERS.STATUS)
                                .from(PURCHASE_ORDER_ITEMS)
                                .join(PURCHASE_ORDERS)
                                .on(
                                        PURCHASE_ORDERS.PURCHASE_ORDER_ID.eq(
                                                PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID))
                                .where(PURCHASE_ORDER_ITEMS.RAW_MATERIAL_ID.in(rawIds)));
        if (items.isEmpty()) return;
        var receipts =
                rows(
                        facts,
                        "inbound",
                        "inbound_id",
                        dsl.select(
                                        INBOUND.INBOUND_ID,
                                        INBOUND.RAW_MATERIAL_ID,
                                        INBOUND.SUPPLIER_ID,
                                        INBOUND.WAREHOUSE_ID,
                                        INBOUND.PURCHASE_ORDER_ITEM_ID,
                                        INBOUND.QUANTITY,
                                        INBOUND.INBOUND_DATE,
                                        INBOUND.EXPIRY_DATE,
                                        INBOUND.STATUS,
                                        INBOUND.STATUS_REASON,
                                        INBOUND.STATUS_DECIDED_BY,
                                        INBOUND.STATUS_DECIDED_AT)
                                .from(INBOUND)
                                .where(
                                        INBOUND.PURCHASE_ORDER_ITEM_ID.in(
                                                items.stream()
                                                        .map(
                                                                row ->
                                                                        id(
                                                                                row,
                                                                                "purchase_order_item_id"))
                                                        .toList())));
        for (var item : items) {
            long itemId = id(item, "purchase_order_item_id");
            var received =
                    receipts.stream()
                            .filter(row -> id(row, "purchase_order_item_id") == itemId)
                            .toList();
            for (var receipt : received)
                require(
                        id(receipt, "raw_material_id") == id(item, "raw_material_id")
                                && id(receipt, "supplier_id") == id(item, "supplier_id"),
                        "PO_RECEIPT_IDENTITY_MISMATCH: inbound:" + id(receipt, "inbound_id"));
            BigDecimal remaining =
                    decimal(item, "quantity").subtract(decimal(item, "received_quantity"));
            require(
                    remaining.signum() >= 0
                            && sum(received, "quantity")
                                            .compareTo(decimal(item, "received_quantity"))
                                    == 0,
                    "PO_RECEIPT_MISMATCH: purchase_order_items:" + itemId);
            LocalDate due = date(item, "expected_delivery_date");
            if (Set.of("ORDERED", "PARTIAL").contains(str(item, "status"))
                    && remaining.signum() > 0
                    && due != null
                    && !due.isBefore(start)
                    && !due.isAfter(end)) {
                if (item.get("purchase_warehouse_id") != null
                        && id(item, "purchase_warehouse_id") != warehouse) continue;
                supply.add(
                        new BomPlanner.StockLot(
                                "purchase_order_items:" + itemId,
                                materialItem(materials.get(id(item, "raw_material_id"))),
                                remaining,
                                due,
                                null,
                                null,
                                true));
            }
        }
    }

    private void loadRawSupply(
            Map<String, SourceFact> facts,
            long warehouse,
            Map<Long, Map<String, Object>> materials,
            List<BomPlanner.StockLot> supply) {
        var receipts =
                rows(
                        facts,
                        "inbound",
                        "inbound_id",
                        dsl.select(
                                        INBOUND.INBOUND_ID,
                                        INBOUND.RAW_MATERIAL_ID,
                                        INBOUND.SUPPLIER_ID,
                                        INBOUND.WAREHOUSE_ID,
                                        INBOUND.PURCHASE_ORDER_ITEM_ID,
                                        INBOUND.QUANTITY,
                                        INBOUND.INBOUND_DATE,
                                        INBOUND.EXPIRY_DATE,
                                        INBOUND.STATUS,
                                        INBOUND.STATUS_REASON,
                                        INBOUND.STATUS_DECIDED_BY,
                                        INBOUND.STATUS_DECIDED_AT)
                                .from(INBOUND)
                                .where(
                                        INBOUND.WAREHOUSE_ID
                                                .eq(warehouse)
                                                .and(
                                                        INBOUND.RAW_MATERIAL_ID
                                                                .in(materials.keySet())
                                                                .or(
                                                                        INBOUND.INBOUND_ID.in(
                                                                                select(
                                                                                                RAW_MATERIAL_LOTS
                                                                                                        .INBOUND_ID)
                                                                                        .from(
                                                                                                RAW_MATERIAL_LOTS)
                                                                                        .where(
                                                                                                RAW_MATERIAL_LOTS
                                                                                                        .RAW_MATERIAL_ID
                                                                                                        .in(
                                                                                                                materials
                                                                                                                        .keySet())))))));
        if (receipts.isEmpty()) return;
        // Read every sibling LOT, including mislinked materials, and receipts with no LOT
        // allocation.
        var lots =
                rows(
                        facts,
                        "raw_material_lots",
                        "raw_material_lot_id",
                        dsl.select(
                                        RAW_MATERIAL_LOTS.RAW_MATERIAL_LOT_ID,
                                        RAW_MATERIAL_LOTS.RAW_MATERIAL_ID,
                                        RAW_MATERIAL_LOTS.INBOUND_ID,
                                        RAW_MATERIAL_LOTS.LOT_NUMBER,
                                        RAW_MATERIAL_LOTS.QUANTITY,
                                        RAW_MATERIAL_LOTS.REMAINING_QUANTITY,
                                        RAW_MATERIAL_LOTS.PRODUCTION_DATE,
                                        RAW_MATERIAL_LOTS.EXPIRY_DATE,
                                        INBOUND.RAW_MATERIAL_ID.as("inbound_material_id"),
                                        INBOUND.WAREHOUSE_ID,
                                        INBOUND.INBOUND_DATE,
                                        INBOUND.STATUS.as("inbound_status"),
                                        INBOUND.PURCHASE_ORDER_ITEM_ID,
                                        INBOUND.SUPPLIER_ID.as("inbound_supplier_id"),
                                        PURCHASE_ORDER_ITEMS.RAW_MATERIAL_ID.as(
                                                "purchase_material_id"),
                                        PURCHASE_ORDERS.SUPPLIER_ID.as("purchase_supplier_id"))
                                .from(RAW_MATERIAL_LOTS)
                                .join(INBOUND)
                                .on(INBOUND.INBOUND_ID.eq(RAW_MATERIAL_LOTS.INBOUND_ID))
                                .join(PURCHASE_ORDER_ITEMS)
                                .on(
                                        PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ITEM_ID.eq(
                                                INBOUND.PURCHASE_ORDER_ITEM_ID))
                                .join(PURCHASE_ORDERS)
                                .on(
                                        PURCHASE_ORDERS.PURCHASE_ORDER_ID.eq(
                                                PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID))
                                .where(
                                        INBOUND.INBOUND_ID.in(
                                                receipts.stream()
                                                        .map(row -> id(row, "inbound_id"))
                                                        .toList())));
        for (var receipt : receipts) {
            long receiptId = id(receipt, "inbound_id");
            BigDecimal allocated =
                    sum(
                            lots.stream()
                                    .filter(row -> id(row, "inbound_id") == receiptId)
                                    .toList(),
                            "quantity");
            require(
                    allocated.compareTo(decimal(receipt, "quantity")) == 0,
                    "RAW_RECEIPT_LOT_MISMATCH: inbound:" + receiptId);
        }
        var inputs =
                rows(
                        facts,
                        "production_ingredients",
                        "production_ingredient_id",
                        dsl.select(
                                        PRODUCTION_INGREDIENTS.PRODUCTION_INGREDIENT_ID,
                                        PRODUCTION_INGREDIENTS.PRODUCTION_RECORD_ID,
                                        PRODUCTION_INGREDIENTS.RAW_MATERIAL_LOT_ID,
                                        PRODUCTION_INGREDIENTS.QUANTITY_USED,
                                        PRODUCTION_RECORDS.LOT_ID.as("target_lot_id"),
                                        PRODUCTION_RECORDS.WAREHOUSE_ID.as("target_warehouse_id"))
                                .from(PRODUCTION_INGREDIENTS)
                                .join(PRODUCTION_RECORDS)
                                .on(
                                        PRODUCTION_RECORDS.PRODUCTION_RECORD_ID.eq(
                                                PRODUCTION_INGREDIENTS.PRODUCTION_RECORD_ID))
                                .where(
                                        PRODUCTION_INGREDIENTS.RAW_MATERIAL_LOT_ID.in(
                                                lots.stream()
                                                        .map(row -> id(row, "raw_material_lot_id"))
                                                        .toList())));
        for (var lot : lots) {
            long lotId = id(lot, "raw_material_lot_id");
            require(
                    id(lot, "raw_material_id") == id(lot, "inbound_material_id")
                            && id(lot, "inbound_material_id") == id(lot, "purchase_material_id")
                            && id(lot, "inbound_supplier_id") == id(lot, "purchase_supplier_id"),
                    "RAW_LOT_IDENTITY_MISMATCH: raw_material_lots:" + lotId);
            var used =
                    inputs.stream().filter(row -> id(row, "raw_material_lot_id") == lotId).toList();
            BigDecimal residual = decimal(lot, "quantity").subtract(sum(used, "quantity_used"));
            require(
                    residual.signum() >= 0
                            && residual.compareTo(decimal(lot, "remaining_quantity")) == 0,
                    "RAW_LOT_REMAINING_MISMATCH: raw_material_lots:" + lotId);
            for (var input : used)
                require(
                        id(input, "target_warehouse_id") == warehouse,
                        "RAW_INPUT_WAREHOUSE_MISMATCH: production_ingredients:"
                                + id(input, "production_ingredient_id"));
            String status = str(lot, "inbound_status");
            supply.add(
                    new BomPlanner.StockLot(
                            "raw_material_lots:" + lotId,
                            materialItem(materials.get(id(lot, "raw_material_id"))),
                            decimal(lot, "remaining_quantity"),
                            date(lot, "inbound_date"),
                            date(lot, "expiry_date"),
                            status.equals("RELEASED") ? null : "INBOUND_" + status,
                            false));
        }
    }

    private List<ProductInput> loadDemand(
            Map<String, SourceFact> facts,
            Set<Long> roots,
            Map<Long, Map<String, Object>> products,
            List<Map<String, Object>> shipments,
            LocalDate asOf) {
        var lines =
                rows(
                        facts,
                        "order_items",
                        "orders_item_id",
                        dsl.select(
                                        ORDER_ITEMS.ORDERS_ITEM_ID,
                                        ORDER_ITEMS.ORDER_ID,
                                        ORDER_ITEMS.PRODUCT_ID,
                                        ORDER_ITEMS.QUANTITY,
                                        ORDER_ITEMS.UNIT_PRICE,
                                        ORDERS.ORDER_DATE,
                                        ORDERS.EXPECTED_DELIVERY_DATE,
                                        ORDERS.STATUS)
                                .from(ORDER_ITEMS)
                                .join(ORDERS)
                                .on(ORDERS.ORDER_ID.eq(ORDER_ITEMS.ORDER_ID))
                                .where(
                                        ORDER_ITEMS
                                                .PRODUCT_ID
                                                .in(roots)
                                                .and(
                                                        ORDERS.ORDER_DATE
                                                                .ge(asOf.minusDays(56))
                                                                .and(ORDERS.ORDER_DATE.lt(asOf))
                                                                .or(
                                                                        ORDERS.STATUS.eq(
                                                                                OrderStatus
                                                                                        .CONFIRMED)))));
        var result = new ArrayList<ProductInput>();
        for (long product : roots) {
            var selected =
                    lines.stream()
                            .filter(row -> id(row, "product_id") == product)
                            .sorted(Comparator.comparingLong(row -> id(row, "orders_item_id")))
                            .toList();
            var history =
                    selected.stream()
                            .filter(
                                    row ->
                                            date(row, "order_date").isBefore(asOf)
                                                    && !date(row, "order_date")
                                                            .isBefore(asOf.minusDays(56)))
                            .map(
                                    row ->
                                            new ForecastService.HistoricalOrder(
                                                    date(row, "order_date"),
                                                    str(row, "status"),
                                                    decimal(row, "quantity"),
                                                    "order_items:" + id(row, "orders_item_id")))
                            .toList();
            var confirmed = new TreeMap<Long, List<Map<String, Object>>>();
            selected.stream()
                    .filter(row -> str(row, "status").equals("CONFIRMED"))
                    .forEach(
                            row ->
                                    confirmed
                                            .computeIfAbsent(
                                                    id(row, "order_id"),
                                                    ignored -> new ArrayList<>())
                                            .add(row));
            var open = new ArrayList<ForecastService.OpenOrder>();
            for (var entry : confirmed.entrySet()) {
                BigDecimal shipped =
                        shipments.stream()
                                .filter(
                                        row ->
                                                id(row, "order_id") == entry.getKey()
                                                        && id(row, "product_id") == product)
                                .map(row -> decimal(row, "quantity"))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                open.add(
                        new ForecastService.OpenOrder(
                                date(entry.getValue().getFirst(), "expected_delivery_date"),
                                "CONFIRMED",
                                sum(entry.getValue(), "quantity"),
                                shipped,
                                "order_product:" + entry.getKey() + ":" + product));
            }
            var master = products.get(product);
            result.add(
                    new ProductInput(
                            productItem(master),
                            str(master, "sku"),
                            str(master, "name"),
                            history,
                            open));
        }
        return result;
    }

    private void loadSuppliers(
            Map<String, SourceFact> facts,
            Map<Long, Map<String, Object>> materials,
            Map<Long, List<SupplierSelectionService.SupplierTerm>> terms,
            List<SupplierSelectionService.Certificate> certificates) {
        var source =
                rows(
                        facts,
                        "supplier_material_terms",
                        "supplier_material_term_id",
                        dsl.select(
                                        SUPPLIER_MATERIAL_TERMS.SUPPLIER_MATERIAL_TERM_ID,
                                        SUPPLIER_MATERIAL_TERMS.RAW_MATERIAL_ID,
                                        SUPPLIER_MATERIAL_TERMS.SUPPLIER_ID,
                                        SUPPLIER_MATERIAL_TERMS.PURCHASE_UNIT,
                                        SUPPLIER_MATERIAL_TERMS.BASE_QUANTITY_PER_PURCHASE_UNIT,
                                        SUPPLIER_MATERIAL_TERMS.UNIT_PRICE,
                                        SUPPLIER_MATERIAL_TERMS.CURRENCY,
                                        SUPPLIER_MATERIAL_TERMS.MINIMUM_ORDER_QUANTITY,
                                        SUPPLIER_MATERIAL_TERMS.ORDER_MULTIPLE,
                                        SUPPLIER_MATERIAL_TERMS.LEAD_TIME_DAYS,
                                        SUPPLIER_MATERIAL_TERMS.VALID_FROM,
                                        SUPPLIER_MATERIAL_TERMS.VALID_TO,
                                        SUPPLIER_MATERIAL_TERMS.REQUIRED_CERT_TYPES,
                                        SUPPLIER_MATERIAL_TERMS.IS_ACTIVE,
                                        SUPPLIERS.IS_ACTIVE.as("supplier_active"),
                                        SUPPLIERS.NAME.as("supplier_name"))
                                .from(SUPPLIER_MATERIAL_TERMS)
                                .join(SUPPLIERS)
                                .on(SUPPLIERS.SUPPLIER_ID.eq(SUPPLIER_MATERIAL_TERMS.SUPPLIER_ID))
                                .where(
                                        SUPPLIER_MATERIAL_TERMS.RAW_MATERIAL_ID.in(
                                                materials.keySet())));
        for (long material : materials.keySet()) terms.put(material, new ArrayList<>());
        source.sort(Comparator.comparingLong(row -> id(row, "supplier_material_term_id")));
        for (var row : source) {
            long material = id(row, "raw_material_id");
            terms.get(material)
                    .add(
                            new SupplierSelectionService.SupplierTerm(
                                    id(row, "supplier_id"),
                                    id(row, "supplier_material_term_id"),
                                    bool(row, "is_active") && bool(row, "supplier_active"),
                                    str(row, "purchase_unit"),
                                    decimal(row, "base_quantity_per_purchase_unit"),
                                    str(materials.get(material), "unit"),
                                    decimal(row, "unit_price"),
                                    str(row, "currency"),
                                    decimal(row, "minimum_order_quantity"),
                                    decimal(row, "order_multiple"),
                                    integer(row, "lead_time_days"),
                                    date(row, "valid_from"),
                                    date(row, "valid_to"),
                                    certificateTypes(row.get("required_cert_types"))));
        }
        if (source.isEmpty()) return;
        var certRows =
                rows(
                        facts,
                        "supplier_certifications",
                        "supplier_certification_id",
                        dsl.select(
                                        SUPPLIER_CERTIFICATIONS.SUPPLIER_CERTIFICATION_ID,
                                        SUPPLIER_CERTIFICATIONS.SUPPLIER_ID,
                                        SUPPLIER_CERTIFICATIONS.CERT_TYPE,
                                        SUPPLIER_CERTIFICATIONS.CERT_NUMBER,
                                        SUPPLIER_CERTIFICATIONS.ISSUE_DATE,
                                        SUPPLIER_CERTIFICATIONS.EXPIRY_DATE)
                                .from(SUPPLIER_CERTIFICATIONS)
                                .where(
                                        SUPPLIER_CERTIFICATIONS.SUPPLIER_ID.in(
                                                source.stream()
                                                        .map(row -> id(row, "supplier_id"))
                                                        .distinct()
                                                        .sorted()
                                                        .toList())));
        certRows.stream()
                .sorted(Comparator.comparingLong(row -> id(row, "supplier_certification_id")))
                .forEach(
                        row ->
                                certificates.add(
                                        new SupplierSelectionService.Certificate(
                                                id(row, "supplier_id"),
                                                str(row, "cert_type"),
                                                date(row, "issue_date"),
                                                date(row, "expiry_date"),
                                                "supplier_certifications:"
                                                        + id(row, "supplier_certification_id"))));
    }

    private List<Map<String, Object>> rows(
            Map<String, SourceFact> facts,
            String table,
            String key,
            ResultQuery<? extends Record> query) {
        var result = new ArrayList<Map<String, Object>>();
        for (var row : query.fetchMaps()) {
            var values = new TreeMap<String, Object>();
            row.forEach((column, value) -> values.put(column, jsonValue(value)));
            var fact = new SourceFact(table + ":" + values.get(key), values);
            facts.put(fact.sourceRef(), fact);
            result.add(fact.values());
        }
        return result;
    }

    private static Object jsonValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        // Preserve the JDBC timestamp representation used by persisted source-fact hashes.
        if (value instanceof LocalDateTime timestamp)
            return Timestamp.valueOf(timestamp).toString();
        if (value instanceof EnumType enumValue) return enumValue.getLiteral();
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(PlanningSnapshotRepository::jsonValue).toList();
        }
        return value.toString();
    }

    private static Set<String> certificateTypes(Object value) {
        var result = new TreeSet<String>();
        if (value instanceof List<?> types) types.forEach(type -> result.add(type.toString()));
        return result;
    }

    private static void validateUnit(Map<String, Object> row, Set<String> units, String sourceRef) {
        require(units.contains(str(row, "unit")), "UNRECOGNIZED_UNIT: " + sourceRef);
    }

    private static BomPlanner.Item productItem(Map<String, Object> row) {
        return new BomPlanner.Item(
                BomPlanner.Kind.PRODUCT, id(row, "product_id"), str(row, "unit"));
    }

    private static BomPlanner.Item materialItem(Map<String, Object> row) {
        return new BomPlanner.Item(
                BomPlanner.Kind.MATERIAL, id(row, "raw_material_id"), str(row, "unit"));
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> decimal(row, key)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String str(Map<String, Object> row, String key) {
        return row.get(key).toString();
    }

    private static long id(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static int integer(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        return Boolean.TRUE.equals(row.get(key));
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        return (BigDecimal) row.get(key);
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : LocalDate.parse(row.get(key).toString());
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    public record ProductInput(
            BomPlanner.Item item,
            String sku,
            String name,
            List<ForecastService.HistoricalOrder> history,
            List<ForecastService.OpenOrder> openOrders) {
        public ProductInput {
            history = List.copyOf(history);
            openOrders = List.copyOf(openOrders);
        }
    }

    public record SourceFact(String sourceRef, Map<String, Object> values) {
        public SourceFact {
            values = Collections.unmodifiableMap(new TreeMap<>(values));
        }
    }

    public record Snapshot(
            long warehouseId,
            LocalDate asOf,
            int horizonDays,
            int safetyDays,
            LocalDate historyStartDate,
            List<ProductInput> products,
            List<BomPlanner.Bom> boms,
            List<BomPlanner.StockLot> supply,
            Map<Long, List<SupplierSelectionService.SupplierTerm>> supplierTerms,
            List<SupplierSelectionService.Certificate> certificates,
            List<SourceFact> sourceFacts) {
        public Snapshot {
            products = List.copyOf(products);
            boms = List.copyOf(boms);
            supply = List.copyOf(supply);
            var copied = new TreeMap<Long, List<SupplierSelectionService.SupplierTerm>>();
            supplierTerms.forEach((id, terms) -> copied.put(id, List.copyOf(terms)));
            supplierTerms = Collections.unmodifiableMap(copied);
            certificates = List.copyOf(certificates);
            sourceFacts = List.copyOf(sourceFacts);
        }
    }
}
