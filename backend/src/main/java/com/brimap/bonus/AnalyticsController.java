package com.brimap.bonus;

import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.model.*;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bonus 4 - spend analytics by vendor, department or GL account over a date range (CAD, incl. tax for vendor
 * totals; pre-tax line amounts converted to CAD for department/GL). Approved/scheduled/paid bills only.
 * Approvers see their own department only (scope enforced here, not in the UI).
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private static final Set<InvoiceStatus> SPENT = Set.of(InvoiceStatus.APPROVED, InvoiceStatus.SCHEDULED, InvoiceStatus.RELEASED, InvoiceStatus.PAID);

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final VendorRepository vendors;
    private final DepartmentRepository departments;
    private final GlAccountRepository gl;

    public record Row(String key, String label, BigDecimal amountCad, long invoices, BigDecimal share) {}

    @GetMapping("/spend")
    @PreAuthorize("hasAnyRole('FM','ADM','AUD','APR')")
    @Transactional(readOnly = true)
    public Map<String, Object> spend(@RequestParam(defaultValue = "vendor") String groupBy, @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to) {
        CurrentUser u = Auth.require();
        String deptScope = u.role() == Role.APR ? u.departmentId() : null;
        List<Invoice> inv = invoices.findAll().stream().filter(i -> SPENT.contains(i.getStatus()) && i.getInvoiceDate() != null && i.getTotalCad() != null)
                .filter(i -> from == null || !i.getInvoiceDate().isBefore(from)).filter(i -> to == null || !i.getInvoiceDate().isAfter(to))
                .filter(i -> deptScope == null || deptScope.equals(i.getDepartmentId())).toList();
        Map<String, BigDecimal> amount = new HashMap<>();
        Map<String, Set<String>> count = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        switch (groupBy) {
            case "vendor" -> {
                vendors.findAll().forEach(v -> labels.put(v.getId(), v.getLegalName()));
                for (Invoice i : inv) add(amount, count, i.getVendorId() == null ? "unknown" : i.getVendorId(), i.getTotalCad(), i.getId());
            }
            case "dept", "gl" -> {
                if (groupBy.equals("dept")) departments.findAll().forEach(d -> labels.put(d.getId(), d.getName()));
                else gl.findAll().forEach(g -> labels.put(g.getAccountNumber(), g.getName()));
                Map<String, Invoice> byId = inv.stream().collect(Collectors.toMap(Invoice::getId, i -> i));
                for (InvoiceLine l : lines.findAll()) {
                    Invoice i = byId.get(l.getInvoiceId());
                    if (i == null || l.getAmount() == null) continue;
                    BigDecimal rate = i.getFxRateToCad() == null ? BigDecimal.ONE : i.getFxRateToCad();
                    String key = groupBy.equals("dept") ? Optional.ofNullable(l.getDepartmentId()).orElse(i.getDepartmentId()) : l.getGlAccount();
                    if (deptScope != null && !deptScope.equals(groupBy.equals("dept") ? key : i.getDepartmentId())) continue;
                    add(amount, count, key == null ? "uncoded" : key, l.getAmount().multiply(rate).setScale(2, RoundingMode.HALF_UP), i.getId());
                }
            }
            default -> throw new IllegalArgumentException("groupBy must be vendor, dept or gl");
        }
        BigDecimal total = amount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Row> rows = amount.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new Row(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()), e.getValue(), count.get(e.getKey()).size(),
                        total.signum() == 0 ? BigDecimal.ZERO : e.getValue().multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP)))
                .toList();
        // Monthly trend for the same scope
        Map<String, BigDecimal> monthly = new TreeMap<>();
        for (Invoice i : inv) monthly.merge(i.getInvoiceDate().toString().substring(0, 7), i.getTotalCad(), BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupBy", groupBy);
        m.put("scope", deptScope == null ? "all" : deptScope);
        m.put("totalCad", total);
        m.put("rows", rows);
        m.put("monthly", monthly);
        return m;
    }

    private static void add(Map<String, BigDecimal> amount, Map<String, Set<String>> count, String key, BigDecimal v, String invoiceId) {
        amount.merge(key, v, BigDecimal::add);
        count.computeIfAbsent(key, k -> new HashSet<>()).add(invoiceId);
    }
}
