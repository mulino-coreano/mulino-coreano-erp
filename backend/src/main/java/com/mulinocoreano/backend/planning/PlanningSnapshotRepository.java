package com.mulinocoreano.backend.planning;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads one ERP snapshot. A calling transaction must also use REPEATABLE_READ or SERIALIZABLE. */
@Repository
public class PlanningSnapshotRepository {
    private final JdbcClient jdbc;

    public PlanningSnapshotRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = IllegalArgumentException.class)
    public Snapshot load(long warehouseId, List<Long> productIds, LocalDate asOf, int horizonDays) throws IllegalArgumentException {
        require(warehouseId > 0 && productIds != null && !productIds.isEmpty() && asOf != null
                && horizonDays >= 1 && horizonDays <= 90, "INVALID_SNAPSHOT_REQUEST");
        require(productIds.stream().allMatch(id -> id != null && id > 0), "INVALID_PRODUCT_ID");
        String isolation = jdbc.sql("SHOW transaction_isolation").query(String.class).single();
        require(Set.of("repeatable read", "serializable").contains(isolation), "INCOHERENT_SNAPSHOT_TRANSACTION");
        var roots = new TreeSet<>(productIds);
        var facts = new TreeMap<String, SourceFact>();
        Map<String, Object> plant = Map.of("warehouse", warehouseId);
        require(rows(facts, "warehouses", "warehouse_id", "SELECT warehouse_id,name,plant_id FROM warehouses WHERE warehouse_id=:warehouse", plant).size() == 1,
                "UNKNOWN_WAREHOUSE: " + warehouseId);
        var policies = rows(facts, "planning_policies", "warehouse_id", "SELECT warehouse_id,history_start_date,horizon_days,safety_stock_days FROM planning_policies WHERE warehouse_id=:warehouse", plant);
        require(policies.size() == 1, "MISSING_PLANNING_POLICY: " + warehouseId);
        Map<String, Object> policy = policies.getFirst();
        var catalog = rows(facts, "measurement_units", "code", "SELECT code,dimension,to_canonical_factor FROM measurement_units", Map.of());
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
            var productRows = rows(facts, "products", "product_id", "SELECT product_id,sku,name,unit,expiry_days,product_type,is_active FROM products WHERE product_id IN (:ids)", Map.of("ids", current));
            require(productRows.size() == current.size(), "UNKNOWN_PRODUCT");
            for (var row : productRows) {
                long id = id(row, "product_id");
                require(bool(row, "is_active"), "INACTIVE_PRODUCT: " + id);
                require(!roots.contains(id) || str(row, "product_type").equals("FINISHED_GOODS"), "FINISHED_GOODS_REQUIRED: " + id);
                validateUnit(row, units, "products:" + id);
                products.put(id, row);
            }
            var versions = rows(facts, "bom_versions", "bom_version_id", """
                    SELECT bom_version_id,product_id,version,batch_output_quantity,production_lead_days,valid_from,valid_to,is_active
                    FROM bom_versions WHERE product_id IN (:ids) AND is_active
                    AND valid_from <= :end AND (valid_to IS NULL OR valid_to >= :start)
                    """, Map.of("ids", current, "start", asOf, "end", end));
            if (versions.isEmpty()) continue;
            var components = rows(facts, "bom_components", "bom_component_id", "SELECT bom_component_id,bom_version_id,child_product_id,raw_material_id,quantity_per_batch FROM bom_components WHERE bom_version_id IN (:ids)",
                    Map.of("ids", versions.stream().map(row -> id(row, "bom_version_id")).toList()));
            for (var row : components) {
                if (row.get("child_product_id") != null) {
                    long child = id(row, "child_product_id");
                    if (!products.containsKey(child)) pending.add(child);
                } else rawIds.add(id(row, "raw_material_id"));
            }
            // The component unit is resolved after all reachable master rows have been loaded.
        }
        if (!rawIds.isEmpty()) {
            for (var row : rows(facts, "raw_materials", "raw_material_id", "SELECT raw_material_id,name,unit,material_type,supplier_id FROM raw_materials WHERE raw_material_id IN (:ids)", Map.of("ids", rawIds))) {
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
            var componentInputs = facts.values().stream().filter(f -> f.sourceRef().startsWith("bom_components:"))
                    .map(SourceFact::values).filter(row -> id(row, "bom_version_id") == versionId)
                    .sorted(Comparator.comparingLong(row -> id(row, "bom_component_id")))
                    .map(row -> new BomPlanner.Component(row.get("child_product_id") != null
                            ? productItem(products.get(id(row, "child_product_id")))
                            : materialItem(materials.get(id(row, "raw_material_id"))), decimal(row, "quantity_per_batch"))).toList();
            var product = products.get(id(version, "product_id"));
            boms.add(new BomPlanner.Bom(fact.sourceRef(), productItem(product), decimal(version, "batch_output_quantity"),
                    integer(version, "production_lead_days"), integer(product, "expiry_days"), date(version, "valid_from"), date(version, "valid_to"), componentInputs));
        }
        boms.sort(Comparator.comparingLong((BomPlanner.Bom b) -> b.product().id()).thenComparing(BomPlanner.Bom::validFrom).thenComparing(BomPlanner.Bom::ref));
        var outbound = loadOutbound(facts, products.keySet());
        var supply = new ArrayList<BomPlanner.StockLot>();
        loadProductSupply(facts, warehouseId, products, outbound, supply);
        if (!rawIds.isEmpty()) {
            loadPurchases(facts, rawIds, materials, asOf, end, supply);
            loadRawSupply(facts, warehouseId, materials, supply);
        }
        var productInputs = loadDemand(facts, roots, products, outbound, asOf);
        var supplierTerms = new TreeMap<Long, List<SupplierSelectionService.SupplierTerm>>();
        var certificates = new ArrayList<SupplierSelectionService.Certificate>();
        if (!rawIds.isEmpty()) loadSuppliers(facts, materials, supplierTerms, certificates);
        supply.sort(Comparator.comparing(BomPlanner.StockLot::sourceRef));
        return new Snapshot(warehouseId, asOf, horizonDays, integer(policy, "safety_stock_days"), date(policy, "history_start_date"),
                productInputs, boms, supply, supplierTerms, certificates, new ArrayList<>(facts.values()));
    }

    private List<Map<String, Object>> loadOutbound(Map<String, SourceFact> facts, Set<Long> products) {
        var shipments = rows(facts, "outbound", "outbound_id", """
                SELECT outbound_id,product_id,warehouse_id,order_id,quantity,outbound_date FROM outbound
                WHERE product_id IN (:ids) OR outbound_id IN (
                    SELECT ol.outbound_id FROM outbound_lots ol JOIN production_lots pl ON pl.production_lot_id=ol.lot_id WHERE pl.product_id IN (:ids))
                """, Map.of("ids", products));
        if (shipments.isEmpty()) return shipments;
        var allocations = rows(facts, "outbound_lots", "outbound_lot_id", """
                SELECT ol.outbound_lot_id,ol.outbound_id,ol.lot_id,ol.lot_quantity,
                       pl.product_id AS lot_product_id,pl.warehouse_id AS lot_warehouse_id
                FROM outbound_lots ol JOIN production_lots pl ON pl.production_lot_id=ol.lot_id
                WHERE ol.outbound_id IN (:ids)
                """, Map.of("ids", shipments.stream().map(row -> id(row, "outbound_id")).toList()));
        for (var shipment : shipments) {
            long shipmentId = id(shipment, "outbound_id");
            var selected = allocations.stream().filter(row -> id(row, "outbound_id") == shipmentId).toList();
            require(sum(selected, "lot_quantity").compareTo(decimal(shipment, "quantity")) == 0, "OUTBOUND_LOT_MISMATCH: outbound:" + shipmentId);
            for (var row : selected) require(id(row, "lot_product_id") == id(shipment, "product_id")
                            && (row.get("lot_warehouse_id") == null || id(row, "lot_warehouse_id") == id(shipment, "warehouse_id")),
                    "OUTBOUND_LOT_IDENTITY_MISMATCH: outbound_lots:" + id(row, "outbound_lot_id"));
        }
        return shipments;
    }

    private void loadProductSupply(Map<String, SourceFact> facts, long warehouse, Map<Long, Map<String, Object>> products,
                                   List<Map<String, Object>> outbound, List<BomPlanner.StockLot> supply) {
        var lots = rows(facts, "production_lots", "production_lot_id", "SELECT production_lot_id,product_id,warehouse_id,lot_number,quantity,production_date,expiry_date,status FROM production_lots WHERE product_id IN (:ids)", Map.of("ids", products.keySet()));
        var inputs = lots.isEmpty() ? List.<Map<String, Object>>of() : rows(facts, "production_product_inputs", "production_product_input_id", """
                SELECT i.production_product_input_id,i.production_record_id,i.source_production_lot_id,i.quantity_used,
                       r.lot_id AS target_lot_id,r.warehouse_id AS target_warehouse_id
                FROM production_product_inputs i JOIN production_records r USING(production_record_id)
                WHERE i.source_production_lot_id IN (:ids)
                """, Map.of("ids", lots.stream().map(row -> id(row, "production_lot_id")).toList()));
        var stocks = rows(facts, "stock", "stock_id", "SELECT stock_id,product_id,warehouse_id,quantity FROM stock WHERE warehouse_id=:warehouse AND product_id IN (:ids)", Map.of("warehouse", warehouse, "ids", products.keySet()));
        var physical = new HashMap<Long, BigDecimal>();
        for (var lot : lots) {
            long lotId = id(lot, "production_lot_id");
            BigDecimal shipped = facts.values().stream().filter(f -> f.sourceRef().startsWith("outbound_lots:"))
                    .map(SourceFact::values).filter(row -> id(row, "lot_id") == lotId).map(row -> decimal(row, "lot_quantity")).reduce(BigDecimal.ZERO, BigDecimal::add);
            var used = inputs.stream().filter(row -> id(row, "source_production_lot_id") == lotId).toList();
            BigDecimal residual = decimal(lot, "quantity").subtract(shipped).subtract(sum(used, "quantity_used"));
            require(residual.signum() >= 0, "PRODUCT_LOT_OVERCONSUMED: production_lots:" + lotId);
            require(residual.signum() == 0 || lot.get("warehouse_id") != null, "MISSING_LOT_WAREHOUSE: production_lots:" + lotId);
            if (lot.get("warehouse_id") == null || id(lot, "warehouse_id") != warehouse) continue;
            for (var input : used) require(id(input, "target_warehouse_id") == warehouse,
                    "PRODUCT_INPUT_WAREHOUSE_MISMATCH: production_product_inputs:" + id(input, "production_product_input_id"));
            long productId = id(lot, "product_id");
            physical.merge(productId, residual, BigDecimal::add);
            String status = str(lot, "status");
            supply.add(new BomPlanner.StockLot("production_lots:" + lotId, productItem(products.get(productId)), residual,
                    date(lot, "production_date"), date(lot, "expiry_date"), status.equals("ACTIVE") ? null : "PRODUCT_" + status, false));
        }
        for (long productId : products.keySet()) {
            var matching = stocks.stream().filter(row -> id(row, "product_id") == productId).toList();
            require(matching.size() == 1, "MISSING_STOCK: products:" + productId + ":warehouse:" + warehouse);
            require(decimal(matching.getFirst(), "quantity").compareTo(physical.getOrDefault(productId, BigDecimal.ZERO)) == 0,
                    "STOCK_LOT_MISMATCH: products:" + productId + ":warehouse:" + warehouse);
        }
    }

    private void loadPurchases(Map<String, SourceFact> facts, Set<Long> rawIds, Map<Long, Map<String, Object>> materials,
                               LocalDate start, LocalDate end, List<BomPlanner.StockLot> supply) {
        var items = rows(facts, "purchase_order_items", "purchase_order_item_id", """
                SELECT i.purchase_order_item_id,i.purchase_order_id,i.raw_material_id,i.quantity,i.received_quantity,i.unit_price,
                       p.supplier_id,p.order_date,p.expected_delivery_date,p.status
                FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE i.raw_material_id IN (:ids)
                """, Map.of("ids", rawIds));
        if (items.isEmpty()) return;
        var receipts = rows(facts, "inbound", "inbound_id", """
                SELECT inbound_id,raw_material_id,supplier_id,warehouse_id,purchase_order_item_id,quantity,inbound_date,expiry_date,
                       status,status_reason,status_decided_by,status_decided_at
                FROM inbound WHERE purchase_order_item_id IN (:ids)
                """, Map.of("ids", items.stream().map(row -> id(row, "purchase_order_item_id")).toList()));
        for (var item : items) {
            long itemId = id(item, "purchase_order_item_id");
            var received = receipts.stream().filter(row -> id(row, "purchase_order_item_id") == itemId).toList();
            for (var receipt : received) require(id(receipt, "raw_material_id") == id(item, "raw_material_id")
                            && id(receipt, "supplier_id") == id(item, "supplier_id"),
                    "PO_RECEIPT_IDENTITY_MISMATCH: inbound:" + id(receipt, "inbound_id"));
            BigDecimal remaining = decimal(item, "quantity").subtract(decimal(item, "received_quantity"));
            require(remaining.signum() >= 0 && sum(received, "quantity").compareTo(decimal(item, "received_quantity")) == 0,
                    "PO_RECEIPT_MISMATCH: purchase_order_items:" + itemId);
            LocalDate due = date(item, "expected_delivery_date");
            if (Set.of("ORDERED", "PARTIAL").contains(str(item, "status")) && remaining.signum() > 0
                    && due != null && !due.isBefore(start) && !due.isAfter(end)) {
                supply.add(new BomPlanner.StockLot("purchase_order_items:" + itemId, materialItem(materials.get(id(item, "raw_material_id"))),
                        remaining, due, null, null, true));
            }
        }
    }

    private void loadRawSupply(Map<String, SourceFact> facts, long warehouse, Map<Long, Map<String, Object>> materials,
                               List<BomPlanner.StockLot> supply) {
        var receipts = rows(facts, "inbound", "inbound_id", """
                SELECT inbound_id,raw_material_id,supplier_id,warehouse_id,purchase_order_item_id,quantity,inbound_date,expiry_date,
                       status,status_reason,status_decided_by,status_decided_at
                FROM inbound WHERE warehouse_id=:warehouse AND (raw_material_id IN (:ids) OR inbound_id IN (
                    SELECT inbound_id FROM raw_material_lots WHERE raw_material_id IN (:ids)))
                """, Map.of("warehouse", warehouse, "ids", materials.keySet()));
        if (receipts.isEmpty()) return;
        // Read every sibling LOT, including mislinked materials, and receipts with no LOT allocation.
        var lots = rows(facts, "raw_material_lots", "raw_material_lot_id", """
                SELECT r.raw_material_lot_id,r.raw_material_id,r.inbound_id,r.lot_number,r.quantity,r.remaining_quantity,r.production_date,r.expiry_date,
                       i.raw_material_id AS inbound_material_id,i.warehouse_id,i.inbound_date,i.status AS inbound_status,
                       i.purchase_order_item_id,i.supplier_id AS inbound_supplier_id,
                       p.raw_material_id AS purchase_material_id,po.supplier_id AS purchase_supplier_id
                FROM raw_material_lots r JOIN inbound i USING(inbound_id)
                JOIN purchase_order_items p USING(purchase_order_item_id) JOIN purchase_orders po USING(purchase_order_id)
                WHERE i.inbound_id IN (:ids)
                """, Map.of("ids", receipts.stream().map(row -> id(row, "inbound_id")).toList()));
        for (var receipt : receipts) {
            long receiptId = id(receipt, "inbound_id");
            BigDecimal allocated = sum(lots.stream().filter(row -> id(row, "inbound_id") == receiptId).toList(), "quantity");
            require(allocated.compareTo(decimal(receipt, "quantity")) == 0, "RAW_RECEIPT_LOT_MISMATCH: inbound:" + receiptId);
        }
        var inputs = rows(facts, "production_ingredients", "production_ingredient_id", """
                SELECT i.production_ingredient_id,i.production_record_id,i.raw_material_lot_id,i.quantity_used,
                       r.lot_id AS target_lot_id,r.warehouse_id AS target_warehouse_id
                FROM production_ingredients i JOIN production_records r USING(production_record_id)
                WHERE i.raw_material_lot_id IN (:ids)
                """, Map.of("ids", lots.stream().map(row -> id(row, "raw_material_lot_id")).toList()));
        for (var lot : lots) {
            long lotId = id(lot, "raw_material_lot_id");
            require(id(lot, "raw_material_id") == id(lot, "inbound_material_id")
                            && id(lot, "inbound_material_id") == id(lot, "purchase_material_id")
                            && id(lot, "inbound_supplier_id") == id(lot, "purchase_supplier_id"),
                    "RAW_LOT_IDENTITY_MISMATCH: raw_material_lots:" + lotId);
            var used = inputs.stream().filter(row -> id(row, "raw_material_lot_id") == lotId).toList();
            BigDecimal residual = decimal(lot, "quantity").subtract(sum(used, "quantity_used"));
            require(residual.signum() >= 0 && residual.compareTo(decimal(lot, "remaining_quantity")) == 0,
                    "RAW_LOT_REMAINING_MISMATCH: raw_material_lots:" + lotId);
            for (var input : used) require(id(input, "target_warehouse_id") == warehouse,
                    "RAW_INPUT_WAREHOUSE_MISMATCH: production_ingredients:" + id(input, "production_ingredient_id"));
            String status = str(lot, "inbound_status");
            supply.add(new BomPlanner.StockLot("raw_material_lots:" + lotId, materialItem(materials.get(id(lot, "raw_material_id"))),
                    decimal(lot, "remaining_quantity"), date(lot, "inbound_date"), date(lot, "expiry_date"),
                    status.equals("RELEASED") ? null : "INBOUND_" + status, false));
        }
    }

    private List<ProductInput> loadDemand(Map<String, SourceFact> facts, Set<Long> roots, Map<Long, Map<String, Object>> products,
                                          List<Map<String, Object>> shipments, LocalDate asOf) {
        var lines = rows(facts, "order_items", "orders_item_id", """
                SELECT i.orders_item_id,i.order_id,i.product_id,i.quantity,i.unit_price,
                       o.order_date,o.expected_delivery_date,o.status
                FROM order_items i JOIN orders o USING(order_id) WHERE i.product_id IN (:ids)
                  AND ((o.order_date >= :start AND o.order_date < :asof) OR o.status='CONFIRMED')
                """, Map.of("ids", roots, "start", asOf.minusDays(56), "asof", asOf));
        var result = new ArrayList<ProductInput>();
        for (long product : roots) {
            var selected = lines.stream().filter(row -> id(row, "product_id") == product)
                    .sorted(Comparator.comparingLong(row -> id(row, "orders_item_id"))).toList();
            var history = selected.stream().filter(row -> date(row, "order_date").isBefore(asOf)
                            && !date(row, "order_date").isBefore(asOf.minusDays(56)))
                    .map(row -> new ForecastService.HistoricalOrder(date(row, "order_date"), str(row, "status"), decimal(row, "quantity"), "order_items:" + id(row, "orders_item_id"))).toList();
            var confirmed = new TreeMap<Long, List<Map<String, Object>>>();
            selected.stream().filter(row -> str(row, "status").equals("CONFIRMED"))
                    .forEach(row -> confirmed.computeIfAbsent(id(row, "order_id"), ignored -> new ArrayList<>()).add(row));
            var open = new ArrayList<ForecastService.OpenOrder>();
            for (var entry : confirmed.entrySet()) {
                BigDecimal shipped = shipments.stream().filter(row -> id(row, "order_id") == entry.getKey() && id(row, "product_id") == product)
                        .map(row -> decimal(row, "quantity")).reduce(BigDecimal.ZERO, BigDecimal::add);
                open.add(new ForecastService.OpenOrder(date(entry.getValue().getFirst(), "expected_delivery_date"), "CONFIRMED",
                        sum(entry.getValue(), "quantity"), shipped, "order_product:" + entry.getKey() + ":" + product));
            }
            var master = products.get(product);
            result.add(new ProductInput(productItem(master), str(master, "sku"), str(master, "name"), history, open));
        }
        return result;
    }

    private void loadSuppliers(Map<String, SourceFact> facts, Map<Long, Map<String, Object>> materials,
                               Map<Long, List<SupplierSelectionService.SupplierTerm>> terms,
                               List<SupplierSelectionService.Certificate> certificates) {
        var source = rows(facts, "supplier_material_terms", "supplier_material_term_id", """
                SELECT t.supplier_material_term_id,t.raw_material_id,t.supplier_id,t.purchase_unit,t.base_quantity_per_purchase_unit,
                       t.unit_price,t.currency,t.minimum_order_quantity,t.order_multiple,t.lead_time_days,t.valid_from,t.valid_to,
                       t.required_cert_types,t.is_active,s.is_active AS supplier_active,s.name AS supplier_name
                FROM supplier_material_terms t JOIN suppliers s USING(supplier_id) WHERE t.raw_material_id IN (:ids)
                """, Map.of("ids", materials.keySet()));
        for (long material : materials.keySet()) terms.put(material, new ArrayList<>());
        source.sort(Comparator.comparingLong(row -> id(row, "supplier_material_term_id")));
        for (var row : source) {
            long material = id(row, "raw_material_id");
            terms.get(material).add(new SupplierSelectionService.SupplierTerm(id(row, "supplier_id"), id(row, "supplier_material_term_id"),
                    bool(row, "is_active") && bool(row, "supplier_active"), str(row, "purchase_unit"), decimal(row, "base_quantity_per_purchase_unit"),
                    str(materials.get(material), "unit"), decimal(row, "unit_price"), str(row, "currency"), decimal(row, "minimum_order_quantity"),
                    decimal(row, "order_multiple"), integer(row, "lead_time_days"), date(row, "valid_from"), date(row, "valid_to"), certificateTypes(row.get("required_cert_types"))));
        }
        if (source.isEmpty()) return;
        var certRows = rows(facts, "supplier_certifications", "supplier_certification_id", "SELECT supplier_certification_id,supplier_id,cert_type,cert_number,issue_date,expiry_date FROM supplier_certifications WHERE supplier_id IN (:ids)",
                Map.of("ids", source.stream().map(row -> id(row, "supplier_id")).distinct().sorted().toList()));
        certRows.stream().sorted(Comparator.comparingLong(row -> id(row, "supplier_certification_id"))).forEach(row ->
                certificates.add(new SupplierSelectionService.Certificate(id(row, "supplier_id"), str(row, "cert_type"), date(row, "issue_date"), date(row, "expiry_date"), "supplier_certifications:" + id(row, "supplier_certification_id"))));
    }

    private List<Map<String, Object>> rows(Map<String, SourceFact> facts, String table, String key, String sql, Map<String, ?> params) {
        var result = new ArrayList<Map<String, Object>>();
        for (var row : jdbc.sql(sql).params(params).query().listOfRows()) {
            var values = new TreeMap<String, Object>();
            row.forEach((column, value) -> values.put(column, jsonValue(value)));
            var fact = new SourceFact(table + ":" + values.get(key), values);
            facts.put(fact.sourceRef(), fact);
            result.add(fact.values());
        }
        return result;
    }

    private static Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Array array) {
            try { return Arrays.stream((Object[]) array.getArray()).map(PlanningSnapshotRepository::jsonValue).toList(); }
            catch (SQLException exception) { throw new IllegalStateException("INVALID_SOURCE_ARRAY", exception); }
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
    private static BomPlanner.Item productItem(Map<String, Object> row) { return new BomPlanner.Item(BomPlanner.Kind.PRODUCT, id(row, "product_id"), str(row, "unit")); }
    private static BomPlanner.Item materialItem(Map<String, Object> row) { return new BomPlanner.Item(BomPlanner.Kind.MATERIAL, id(row, "raw_material_id"), str(row, "unit")); }
    private static BigDecimal sum(List<Map<String, Object>> rows, String key) { return rows.stream().map(row -> decimal(row, key)).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static String str(Map<String, Object> row, String key) { return row.get(key).toString(); }
    private static long id(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static int integer(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    private static boolean bool(Map<String, Object> row, String key) { return Boolean.TRUE.equals(row.get(key)); }
    private static BigDecimal decimal(Map<String, Object> row, String key) { return (BigDecimal) row.get(key); }
    private static LocalDate date(Map<String, Object> row, String key) { return row.get(key) == null ? null : LocalDate.parse(row.get(key).toString()); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }

    public record ProductInput(BomPlanner.Item item, String sku, String name,
                               List<ForecastService.HistoricalOrder> history, List<ForecastService.OpenOrder> openOrders) {
        public ProductInput { history = List.copyOf(history); openOrders = List.copyOf(openOrders); }
    }
    public record SourceFact(String sourceRef, Map<String, Object> values) {
        public SourceFact { values = Collections.unmodifiableMap(new TreeMap<>(values)); }
    }
    public record Snapshot(long warehouseId, LocalDate asOf, int horizonDays, int safetyDays,
                           LocalDate historyStartDate, List<ProductInput> products, List<BomPlanner.Bom> boms,
                           List<BomPlanner.StockLot> supply, Map<Long, List<SupplierSelectionService.SupplierTerm>> supplierTerms,
                           List<SupplierSelectionService.Certificate> certificates, List<SourceFact> sourceFacts) {
        public Snapshot {
            products = List.copyOf(products); boms = List.copyOf(boms); supply = List.copyOf(supply);
            var copied = new TreeMap<Long, List<SupplierSelectionService.SupplierTerm>>();
            supplierTerms.forEach((id, terms) -> copied.put(id, List.copyOf(terms)));
            supplierTerms = Collections.unmodifiableMap(copied);
            certificates = List.copyOf(certificates); sourceFacts = List.copyOf(sourceFacts);
        }
    }
}
