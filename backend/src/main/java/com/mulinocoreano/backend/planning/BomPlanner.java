package com.mulinocoreano.backend.planning;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class BomPlanner {
    public enum Kind { PRODUCT, MATERIAL }
    public record Item(Kind kind, long id, String unit) {}
    public record Demand(Item product, LocalDate needDate, BigDecimal quantity) {}
    public record Component(Item item, BigDecimal quantityPerBatch) {}
    public record Bom(String ref, Item product, BigDecimal batchOutputQuantity, int leadDays,
                      int shelfLifeDays, LocalDate validFrom, LocalDate validTo, List<Component> components) {}
    public record StockLot(String sourceRef, Item item, BigDecimal quantity, LocalDate availableOn,
                           LocalDate expiresOn, String exclusionReason, boolean projected) {}
    public record ProductionRequirement(Item product, LocalDate needDate, LocalDate startDate,
                                        BigDecimal netQuantity, BigDecimal productionQuantity,
                                        BigDecimal batches, String bomRef) {}
    public record MaterialRequirement(Item material, LocalDate needDate, BigDecimal grossQuantity,
                                      BigDecimal suppliedQuantity, BigDecimal netQuantity) {}
    public record Allocation(String sourceRef, Item item, LocalDate needDate, BigDecimal quantity, boolean projected) {}
    public record Exclusion(String sourceRef, Item item, LocalDate needDate, String reason) {}
    public record Result(List<ProductionRequirement> production, List<MaterialRequirement> materials,
                         List<Allocation> allocations, List<Exclusion> exclusions) {}

    public Result plan(LocalDate asOf, List<Demand> demands, List<Bom> boms, List<StockLot> lots) {
        Objects.requireNonNull(asOf, "AS_OF_REQUIRED");
        Objects.requireNonNull(demands, "DEMANDS_REQUIRED");
        Objects.requireNonNull(boms, "BOMS_REQUIRED");
        Objects.requireNonNull(lots, "SUPPLY_REQUIRED");
        var units = new HashMap<String, String>();
        var productDemand = new HashMap<Node, BigDecimal>();
        for (Demand demand : demands) {
            Objects.requireNonNull(demand, "DEMAND_REQUIRED");
            validateItem(demand.product(), units);
            if (demand.product().kind() != Kind.PRODUCT) throw invalid("PRODUCT_DEMAND_REQUIRED");
            Objects.requireNonNull(demand.needDate(), "NEED_DATE_REQUIRED");
            if (demand.needDate().isBefore(asOf)) throw invalid("PAST_DEMAND");
            quantity(demand.quantity());
            if (demand.quantity().signum() > 0) addProduct(productDemand, new Node(demand.product(), demand.needDate()), demand.quantity());
        }
        var byProduct = new HashMap<Item, List<Bom>>();
        var bomRefs = new HashSet<String>();
        for (Bom bom : boms) {
            Objects.requireNonNull(bom, "BOM_REQUIRED");
            validateItem(bom.product(), units);
            if (bom.product().kind() != Kind.PRODUCT || bom.ref() == null || bom.ref().isBlank()) throw invalid("INVALID_BOM");
            if (!bomRefs.add(bom.ref())) throw invalid("DUPLICATE_BOM");
            positive(bom.batchOutputQuantity());
            if (bom.leadDays() < 0 || bom.shelfLifeDays() <= 0) throw invalid("INVALID_PRODUCTION_POLICY");
            if (bom.validFrom() == null || (bom.validTo() != null && bom.validTo().isBefore(bom.validFrom()))) throw invalid("INVALID_BOM_VALIDITY");
            if (bom.components() == null || bom.components().isEmpty()) throw invalid("BOM_COMPONENTS_REQUIRED");
            var components = new HashSet<Item>();
            for (Component component : bom.components()) {
                Objects.requireNonNull(component, "COMPONENT_REQUIRED");
                validateItem(component.item(), units);
                positive(component.quantityPerBatch());
                if (!components.add(component.item())) throw invalid("DUPLICATE_BOM_COMPONENT");
            }
            byProduct.computeIfAbsent(bom.product(), ignored -> new ArrayList<>()).add(bom);
        }
        var pools = new HashMap<Item, List<Balance>>();
        var sourceRefs = new HashSet<String>();
        for (StockLot lot : lots) {
            Objects.requireNonNull(lot, "SUPPLY_LOT_REQUIRED");
            validateItem(lot.item(), units);
            quantity(lot.quantity());
            if (lot.sourceRef() == null || lot.sourceRef().isBlank() || lot.sourceRef().startsWith("PLAN:")) throw invalid("INVALID_SUPPLY_REF");
            if (!sourceRefs.add(lot.sourceRef())) throw invalid("DUPLICATE_SUPPLY");
            if (lot.availableOn() == null || (lot.expiresOn() != null && lot.expiresOn().isBefore(lot.availableOn()))) throw invalid("INVALID_SUPPLY_DATES");
            pools.computeIfAbsent(lot.item(), ignored -> new ArrayList<>()).add(new Balance(lot));
        }

        var production = new ArrayList<ProductionRequirement>();
        var materialDemand = new HashMap<Item, TreeMap<LocalDate, BigDecimal>>();
        var allocations = new ArrayList<Allocation>();
        var exclusions = new ArrayList<Exclusion>();
        LocalDate end = demands.stream().map(Demand::needDate).max(LocalDate::compareTo).orElse(asOf);
        validateEffectiveGraphs(byProduct, asOf, end);
        for (Node node : topologicalOrder(productDemand.keySet(), byProduct, asOf)) {
                Item product = node.product();
                LocalDate needDate = node.date();
                BigDecimal gross = productDemand.getOrDefault(node, BigDecimal.ZERO);
                if (gross.signum() == 0) continue;
                BigDecimal supplied = allocate(product, needDate, gross, pools, allocations, exclusions);
                BigDecimal net = gross.subtract(supplied);
                if (net.signum() == 0) continue;
                List<Bom> effective = byProduct.getOrDefault(product, List.of()).stream()
                        .filter(b -> !b.validFrom().isAfter(needDate) && (b.validTo() == null || !b.validTo().isBefore(needDate))).toList();
                if (effective.isEmpty()) throw invalid("MISSING_BOM: " + product.id());
                if (effective.size() != 1) throw invalid("AMBIGUOUS_BOM: " + product.id());
                Bom bom = effective.getFirst();
                LocalDate start = needDate.minusDays(bom.leadDays());
                if (start.isBefore(asOf)) throw invalid("PRODUCTION_LEAD_TIME: " + product.id());
                if (start.isBefore(bom.validFrom())) throw invalid("BOM_NOT_VALID_FOR_PRODUCTION: " + bom.ref());
                BigDecimal batches = net.divide(bom.batchOutputQuantity(), 0, RoundingMode.CEILING);
                BigDecimal produced = quantize(batches.multiply(bom.batchOutputQuantity()));
                production.add(new ProductionRequirement(product, needDate, start, net, produced, batches, bom.ref()));
                BigDecimal excess = produced.subtract(net);
                if (excess.signum() > 0) {
                    var surplus = new StockLot("PLAN:" + bom.ref() + ":" + needDate, product, excess,
                            needDate, needDate.plusDays(bom.shelfLifeDays()), null, true);
                    pools.computeIfAbsent(product, ignored -> new ArrayList<>()).add(new Balance(surplus));
                }
                for (Component component : bom.components()) {
                    BigDecimal componentQuantity = quantize(component.quantityPerBatch().multiply(batches));
                    if (component.item().kind() == Kind.PRODUCT) addProduct(productDemand, new Node(component.item(), start), componentQuantity);
                    else add(materialDemand, component.item(), start, componentQuantity);
                }
        }
        var materials = new ArrayList<MaterialRequirement>();
        for (Item material : materialDemand.keySet().stream().sorted(ITEM_ORDER).toList()) {
            for (var demand : materialDemand.get(material).entrySet()) {
                BigDecimal supplied = allocate(material, demand.getKey(), demand.getValue(), pools, allocations, exclusions);
                materials.add(new MaterialRequirement(material, demand.getKey(), demand.getValue(), supplied, demand.getValue().subtract(supplied)));
            }
        }
        return new Result(List.copyOf(production), List.copyOf(materials), List.copyOf(allocations), List.copyOf(exclusions));
    }

    private static final Comparator<Item> ITEM_ORDER = Comparator.comparing(Item::kind).thenComparingLong(Item::id);
    private record Node(Item product, LocalDate date) {}
    private static final Comparator<Node> NODE_ORDER = Comparator.comparing(Node::date).thenComparing(Node::product, ITEM_ORDER);
    private static final Comparator<Balance> FEFO = Comparator
            .comparing((Balance b) -> b.lot.expiresOn(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(b -> b.lot.availableOn()).thenComparing(b -> b.lot.sourceRef());

    private static List<Node> topologicalOrder(Set<Node> roots, Map<Item, List<Bom>> boms, LocalDate asOf) {
        // Dependencies are product/date pairs. Uniting historical and future recipes by product
        // creates false cycles and can net a shared component before all its dated parents exist.
        var edges = new HashMap<Node, Set<Node>>();
        var pending = new ArrayDeque<>(roots.stream().sorted(NODE_ORDER).toList());
        while (!pending.isEmpty()) {
            Node parent = pending.removeFirst();
            if (edges.containsKey(parent)) continue;
            var children = new TreeSet<Node>(NODE_ORDER);
            for (Bom bom : effective(boms.getOrDefault(parent.product(), List.of()), parent.date())) {
                LocalDate requiredDate = parent.date().minusDays(bom.leadDays());
                // Stock may satisfy this node; impossible manufacturing is reported only if needed.
                if (requiredDate.isBefore(asOf)) continue;
                for (Component component : bom.components()) {
                    if (component.item().kind() == Kind.PRODUCT) children.add(new Node(component.item(), requiredDate));
                }
            }
            edges.put(parent, children);
            pending.addAll(children);
        }
        // A later-due parent's long lead can create an earlier component need. Date priority
        // alone cannot order that component before another already-ready node of the same item.
        // Add explicit chronological edges after every potential dated requirement is known.
        var timelines = new HashMap<Item, TreeSet<Node>>();
        for (Node node : edges.keySet()) timelines.computeIfAbsent(node.product(), ignored -> new TreeSet<>(NODE_ORDER)).add(node);
        for (var timeline : timelines.values()) {
            Node previous = null;
            for (Node current : timeline) {
                if (previous != null) edges.get(previous).add(current);
                previous = current;
            }
        }
        var indegree = new HashMap<Node, Integer>();
        edges.keySet().forEach(item -> indegree.put(item, 0));
        edges.values().forEach(children -> children.forEach(item -> indegree.merge(item, 1, Integer::sum)));
        var ready = new PriorityQueue<>(NODE_ORDER);
        indegree.forEach((item, count) -> { if (count == 0) ready.add(item); });
        var ordered = new ArrayList<Node>();
        while (!ready.isEmpty()) {
            Node item = ready.remove();
            ordered.add(item);
            for (Node child : edges.get(item)) if (indegree.compute(child, (key, count) -> count - 1) == 0) ready.add(child);
        }
        if (ordered.size() != edges.size()) throw invalid("PLANNING_DEPENDENCY_CYCLE");
        return ordered;
    }

    private static List<Bom> effective(List<Bom> boms, LocalDate date) {
        return boms.stream().filter(b -> !b.validFrom().isAfter(date) && (b.validTo() == null || !b.validTo().isBefore(date))).toList();
    }

    private static void validateEffectiveGraphs(Map<Item, List<Bom>> boms, LocalDate start, LocalDate end) {
        // A positive production lead can unroll an invalid simultaneous cycle into older dates;
        // retain the DB's calendar-effective DAG invariant independently from scheduling edges.
        var boundaries = new TreeSet<LocalDate>();
        boundaries.add(start);
        for (List<Bom> versions : boms.values()) for (Bom bom : versions) {
            if (!bom.validFrom().isBefore(start) && !bom.validFrom().isAfter(end)) boundaries.add(bom.validFrom());
            if (bom.validTo() != null && !bom.validTo().isBefore(start) && bom.validTo().isBefore(end)) boundaries.add(bom.validTo().plusDays(1));
        }
        for (LocalDate date : boundaries) {
            var visiting = new HashSet<Item>();
            var visited = new HashSet<Item>();
            for (Item item : boms.keySet().stream().sorted(ITEM_ORDER).toList()) validateGraphAt(item, date, boms, visiting, visited);
        }
    }

    private static void validateGraphAt(Item item, LocalDate date, Map<Item, List<Bom>> boms, Set<Item> visiting, Set<Item> visited) {
        if (visited.contains(item)) return;
        if (!visiting.add(item)) throw invalid("BOM_CYCLE");
        var versions = effective(boms.getOrDefault(item, List.of()), date);
        if (versions.size() > 1) throw invalid("AMBIGUOUS_BOM: " + item.id());
        for (Bom bom : versions) for (Component component : bom.components()) {
            if (component.item().kind() == Kind.PRODUCT) validateGraphAt(component.item(), date, boms, visiting, visited);
        }
        visiting.remove(item);
        visited.add(item);
    }

    private static BigDecimal allocate(Item item, LocalDate date, BigDecimal quantity,
                                       Map<Item, List<Balance>> pools, List<Allocation> allocations, List<Exclusion> exclusions) {
        var balances = pools.getOrDefault(item, List.of()).stream().sorted(FEFO).toList();
        BigDecimal remaining = quantity;
        for (Balance balance : balances) {
            if (balance.remaining.signum() == 0) continue;
            StockLot lot = balance.lot;
            String reason = lot.exclusionReason();
            if (reason == null && lot.availableOn().isAfter(date)) reason = "NOT_YET_AVAILABLE";
            if (reason == null && lot.expiresOn() != null && lot.expiresOn().isBefore(date)) reason = "EXPIRED_BEFORE_USE";
            if (reason != null) {
                exclusions.add(new Exclusion(lot.sourceRef(), item, date, reason));
                continue;
            }
            if (remaining.signum() == 0) continue;
            BigDecimal take = remaining.min(balance.remaining);
            balance.remaining = balance.remaining.subtract(take);
            remaining = remaining.subtract(take);
            allocations.add(new Allocation(lot.sourceRef(), item, date, take, lot.projected()));
        }
        return quantity.subtract(remaining);
    }

    private static void add(Map<Item, TreeMap<LocalDate, BigDecimal>> demand, Item item, LocalDate date, BigDecimal quantity) {
        demand.computeIfAbsent(item, ignored -> new TreeMap<>()).merge(date, quantity, (a, b) -> quantize(a.add(b)));
    }

    private static void addProduct(Map<Node, BigDecimal> demand, Node node, BigDecimal quantity) {
        demand.merge(node, quantity, (a, b) -> quantize(a.add(b)));
    }

    private static void validateItem(Item item, Map<String, String> units) {
        if (item == null || item.kind() == null || item.id() <= 0 || item.unit() == null || item.unit().isBlank()) throw invalid("INVALID_ITEM");
        String previous = units.putIfAbsent(item.kind() + ":" + item.id(), item.unit());
        if (previous != null && !previous.equals(item.unit())) throw invalid("ITEM_UNIT_MISMATCH");
    }

    private static void positive(BigDecimal value) {
        quantity(value);
        if (value.signum() == 0) throw invalid("POSITIVE_QUANTITY_REQUIRED");
    }

    private static void quantity(BigDecimal value) {
        if (value == null) throw invalid("QUANTITY_REQUIRED");
        if (value.signum() < 0) throw invalid("NEGATIVE_QUANTITY");
        if (value.stripTrailingZeros().scale() > 6) throw invalid("UNSUPPORTED_QUANTITY_PRECISION");
        quantize(value);
    }

    private static BigDecimal quantize(BigDecimal value) {
        BigDecimal result = value.setScale(6, RoundingMode.CEILING);
        if (result.precision() > 18) throw invalid("QUANTITY_OVERFLOW");
        return result;
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    private static final class Balance {
        final StockLot lot;
        BigDecimal remaining;
        Balance(StockLot lot) { this.lot = lot; this.remaining = lot.quantity(); }
    }
}
