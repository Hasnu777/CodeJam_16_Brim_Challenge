package com.brimap.coding;

import com.brimap.audit.AuditService;
import com.brimap.intake.InvoiceProcessingStep;
import com.brimap.model.*;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Applies CodingSuggester to every line of an invoice. Lines the clerk overrode are never touched. */
@Service
@RequiredArgsConstructor
public class CodingService implements InvoiceProcessingStep {
    private final InvoiceLineRepository lines;
    private final InvoiceRepository invoices;
    private final VendorRepository vendors;
    private final GlAccountRepository glAccounts;
    private final AuditService audit;

    @Override
    public int order() { return 10; }

    @Override
    @Transactional
    public void process(Invoice invoice) {
        Invoice inv = invoices.findById(invoice.getId()).orElseThrow();
        Vendor v = inv.getVendorId() == null ? null : vendors.findById(inv.getVendorId()).orElse(null);
        List<CodingSuggester.HistoryLine> history = history();
        Map<String, String> glNames = glAccounts.findAll().stream().collect(Collectors.toMap(GlAccount::getAccountNumber, GlAccount::getName));
        List<Map<String, String>> applied = new ArrayList<>();
        for (InvoiceLine l : lines.findByInvoiceIdOrderByLineNo(inv.getId())) {
            var s = CodingSuggester.suggest(l, inv.getVendorId(), v == null ? inv.getVendorNameRaw() : v.getLegalName(),
                    v == null ? null : v.getDefaultGlAccount(), inv.getDepartmentId(), history, glNames);
            l.setSuggestedGlAccount(s.glAccount());
            l.setSuggestedDepartmentId(s.departmentId());
            l.setSuggestionReason(s.reason());
            if (!l.isSuggestionOverridden()) {
                // A new suggestion replaces what the clerk confirmed: it needs confirming again.
                if (!java.util.Objects.equals(l.getGlAccount(), s.glAccount()) || !java.util.Objects.equals(l.getDepartmentId(), s.departmentId())) l.setCodingConfirmed(false);
                l.setGlAccount(s.glAccount());
                l.setDepartmentId(s.departmentId());
            }
            lines.save(l);
            applied.add(Map.of("line", String.valueOf(l.getLineNo()), "gl", String.valueOf(s.glAccount()),
                    "dept", String.valueOf(s.departmentId()), "source", s.source()));
        }
        if (!applied.isEmpty()) audit.record("CODING_SUGGESTED", "invoice", inv.getId(), null, applied, applied.size() + " lines coded from history");
    }

    private List<CodingSuggester.HistoryLine> history() {
        Map<String, Invoice> hist = invoices.findAll().stream().filter(Invoice::isHistorical).collect(Collectors.toMap(Invoice::getId, i -> i));
        return lines.findAll().stream().filter(l -> hist.containsKey(l.getInvoiceId()) && l.getGlAccount() != null)
                .map(l -> new CodingSuggester.HistoryLine(l.getInvoiceId(), hist.get(l.getInvoiceId()).getVendorId(), l.getDescription(),
                        l.getGlAccount(), l.getDepartmentId()))
                .toList();
    }
}
