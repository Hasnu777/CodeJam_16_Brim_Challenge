package com.brimap.admin;

import com.brimap.seed.DataSeeder;
import com.brimap.seed.SeedReport;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final DataSeeder seeder;
    private final EntityManager em;
    private final com.brimap.audit.AuditService audit;


    @org.springframework.beans.factory.annotation.Value("${app.demo-mode:false}")
    private boolean demoMode;

    /** Wipe every table (audit log included) and reload the dataset. Demo only. */
    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADM')")
    public SeedReport reset() {
        if (!demoMode) throw new com.brimap.auth.ForbiddenException("Reset is disabled outside demo mode (set APP_DEMO_MODE=true)");
        String actor = com.brimap.auth.Auth.actorId();
        SeedReport r = seeder.reset();
        // The audit log restarts with the reset itself as event 1, so the chain is never silently empty.
        audit.recordStandalone(actor, "DEMO_RESET", "system", "database", "All tables truncated and dataset reloaded");
        return r;
    }

    /** Live row counts per table vs the dataset README. */
    @GetMapping("/seed-report")
    @PreAuthorize("hasAnyRole('ADM','AUD')")
    public Map<String, Object> seedReport() {
        Map<String, String> tableFor = new LinkedHashMap<>();
        tableFor.put("approval_policies", "approval_policies");
        tableFor.put("approvals", "approval_steps where historical");
        tableFor.put("bank_change_history", "bank_change_requests");
        tableFor.put("bank_statement", "bank_statement_lines");
        tableFor.put("card_transactions", "card_transactions");
        tableFor.put("contracts", "contracts");
        tableFor.put("departments", "departments");
        tableFor.put("fx_rates", "fx_rates");
        tableFor.put("gl_accounts", "gl_accounts");
        tableFor.put("invoice_lines", "invoice_lines l join invoices i on i.id = l.invoice_id where i.historical");
        tableFor.put("invoices", "invoices where historical");
        tableFor.put("payments", "payments where historical");
        tableFor.put("purchase_orders", "purchase_orders");
        tableFor.put("tax_codes", "tax_codes");
        tableFor.put("users", "users");
        tableFor.put("vendors", "vendors where id <= 'V056'");
        tableFor.put("emails", "emails");
        Map<String, Object> rows = new LinkedHashMap<>();
        boolean ok = true;
        for (var e : tableFor.entrySet()) {
            long n = ((Number) em.createNativeQuery("select count(*) from " + e.getValue()).getSingleResult()).longValue();
            Integer exp = DataSeeder.EXPECTED.get(e.getKey());
            ok &= exp != null && exp == n;
            rows.put(e.getKey(), Map.of("expected", exp, "actual", n, "match", exp != null && exp == n));
        }
        return Map.of("allMatch", ok, "tables", rows);
    }
}
