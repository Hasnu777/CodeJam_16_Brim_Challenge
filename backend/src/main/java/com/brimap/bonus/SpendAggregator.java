package com.brimap.bonus;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceLine;
import com.brimap.model.InvoiceStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bonus 4 - pure spend aggregation (no Spring, no DB) so the numbers are unit-tested.
 * <ul>
 *   <li>vendor / category: whole-bill totals in CAD, tax included (what the company actually pays the vendor).</li>
 *   <li>dept / gl: pre-tax line amounts converted to CAD at the bill's rate (tax is not a department's expense, and
 *       one bill can be split across departments and accounts).</li>
 * </ul>
 * Only approved / scheduled / released / paid bills count. The monthly trend uses the same amounts as the rows, so it
 * always adds up to the headline total.
 */
public final class SpendAggregator {
    static final Set<InvoiceStatus> SPENT = Set.of(InvoiceStatus.APPROVED, InvoiceStatus.SCHEDULED, InvoiceStatus.RELEASED, InvoiceStatus.PAID);
    static final String UNKNOWN_VENDOR = "unknown", UNCATEGORIZED = "Uncategorized", UNCODED = "uncoded";

    public enum GroupBy {
        VENDOR, CATEGORY, DEPT, GL;

        public static GroupBy parse(String s) {
            for (GroupBy g : values()) if (g.name().equalsIgnoreCase(s == null ? "" : s.trim())) return g;
            throw new IllegalArgumentException("groupBy must be vendor, category, dept or gl");
        }

        public boolean lineLevel() { return this == DEPT || this == GL; }
    }

    /** invoiceIds: the bills behind the row, so the UI can open exactly those (drill-down). */
    public record Row(String key, String label, BigDecimal amountCad, long invoices, BigDecimal share, List<String> invoiceIds) {}

    public record Result(String groupBy, String scope, BigDecimal totalCad, long invoiceCount, List<Row> rows, Map<String, BigDecimal> monthly) {}

    /** Reference data the aggregation needs: display labels per key and each vendor's category. */
    public record Reference(Map<String, String> labels, Map<String, String> vendorCategory) {}

    private SpendAggregator() {}

    public static Result aggregate(GroupBy groupBy, Collection<Invoice> allInvoices, Collection<InvoiceLine> allLines, Reference ref,
                                   LocalDate from, LocalDate to, String deptScope) {
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must be on or before to");
        List<Invoice> inv = allInvoices.stream()
                .filter(i -> SPENT.contains(i.getStatus()) && i.getInvoiceDate() != null && i.getTotalCad() != null)
                .filter(i -> from == null || !i.getInvoiceDate().isBefore(from))
                .filter(i -> to == null || !i.getInvoiceDate().isAfter(to))
                .filter(i -> deptScope == null || deptScope.equals(i.getDepartmentId()))
                .toList();

        Map<String, BigDecimal> amount = new HashMap<>();
        Map<String, Set<String>> invoicesPerKey = new HashMap<>();
        Map<String, BigDecimal> monthly = new TreeMap<>();
        Set<String> counted = new HashSet<>();

        if (!groupBy.lineLevel()) {
            for (Invoice i : inv) {
                String key = switch (groupBy) {
                    case VENDOR -> i.getVendorId() == null ? UNKNOWN_VENDOR : i.getVendorId();
                    default -> i.getVendorId() == null ? UNCATEGORIZED
                            : Optional.ofNullable(ref.vendorCategory().get(i.getVendorId())).filter(c -> !c.isBlank()).orElse(UNCATEGORIZED);
                };
                add(amount, invoicesPerKey, monthly, counted, key, i.getTotalCad(), i);
            }
        } else {
            Map<String, Invoice> byId = inv.stream().collect(Collectors.toMap(Invoice::getId, i -> i, (a, b) -> a));
            for (InvoiceLine l : allLines) {
                Invoice i = byId.get(l.getInvoiceId());
                if (i == null || l.getAmount() == null) continue;
                BigDecimal rate = i.getFxRateToCad() == null ? BigDecimal.ONE : i.getFxRateToCad();
                String key = groupBy == GroupBy.DEPT ? Optional.ofNullable(l.getDepartmentId()).orElse(i.getDepartmentId()) : l.getGlAccount();
                // An approver sees their own department's share of a bill, not lines charged to other departments.
                if (deptScope != null && groupBy == GroupBy.DEPT && !deptScope.equals(key)) continue;
                add(amount, invoicesPerKey, monthly, counted, key == null ? UNCODED : key, l.getAmount().multiply(rate).setScale(2, RoundingMode.HALF_UP), i);
            }
        }

        BigDecimal total = amount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Row> rows = amount.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new Row(e.getKey(), groupBy == GroupBy.CATEGORY ? e.getKey() : ref.labels().getOrDefault(e.getKey(), e.getKey()),
                        e.getValue(), invoicesPerKey.get(e.getKey()).size(), share(e.getValue(), total),
                        invoicesPerKey.get(e.getKey()).stream().sorted().toList()))
                .toList();
        return new Result(groupBy.name().toLowerCase(Locale.ROOT), deptScope == null ? "all" : deptScope, total, counted.size(), rows, monthly);
    }

    static BigDecimal share(BigDecimal part, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO : part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static void add(Map<String, BigDecimal> amount, Map<String, Set<String>> invoicesPerKey, Map<String, BigDecimal> monthly,
                            Set<String> counted, String key, BigDecimal v, Invoice i) {
        amount.merge(key, v, BigDecimal::add);
        invoicesPerKey.computeIfAbsent(key, k -> new HashSet<>()).add(i.getId());
        monthly.merge(i.getInvoiceDate().toString().substring(0, 7), v, BigDecimal::add);
        counted.add(i.getId());
    }
}
