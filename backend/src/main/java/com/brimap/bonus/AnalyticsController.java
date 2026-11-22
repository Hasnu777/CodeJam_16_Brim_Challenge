package com.brimap.bonus;

import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.model.Role;
import com.brimap.model.Vendor;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bonus 4 - spend analytics by vendor, vendor category, department or GL account over a date range (CAD).
 * The arithmetic lives in {@link SpendAggregator}; this class only loads data and enforces scope:
 * approvers see their own department only (enforced here, not in the UI).
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final VendorRepository vendors;
    private final DepartmentRepository departments;
    private final GlAccountRepository gl;

    @GetMapping("/spend")
    @PreAuthorize("hasAnyRole('FM','ADM','AUD','APR')")
    @Transactional(readOnly = true)
    public SpendAggregator.Result spend(@RequestParam(defaultValue = "vendor") String groupBy, @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to) {
        CurrentUser u = Auth.require();
        SpendAggregator.GroupBy g = SpendAggregator.GroupBy.parse(groupBy);
        String deptScope = u.role() == Role.APR ? u.departmentId() : null;

        Map<String, String> labels = new HashMap<>();
        Map<String, String> vendorCategory = new HashMap<>();
        for (Vendor v : vendors.findAll()) {
            vendorCategory.put(v.getId(), v.getCategory());
            if (g == SpendAggregator.GroupBy.VENDOR) labels.put(v.getId(), v.getLegalName());
        }
        if (g == SpendAggregator.GroupBy.DEPT) departments.findAll().forEach(d -> labels.put(d.getId(), d.getName()));
        if (g == SpendAggregator.GroupBy.GL) gl.findAll().forEach(a -> labels.put(a.getAccountNumber(), a.getName()));

        return SpendAggregator.aggregate(g, invoices.findAll(), g.lineLevel() ? lines.findAll() : List.of(),
                new SpendAggregator.Reference(labels, vendorCategory), from, to, deptScope);
    }
}
