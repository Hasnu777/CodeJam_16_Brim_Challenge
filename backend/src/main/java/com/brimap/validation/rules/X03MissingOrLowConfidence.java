package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.*;

/** X03: a required field (vendor, invoice #, date, total, department) is missing or was read with low confidence. */
@Component
public class X03MissingOrLowConfidence implements ValidationRule {
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("vendor", "Supplier name");
        LABELS.put("invoiceNumber", "Invoice number");
        LABELS.put("invoiceDate", "Invoice date");
        LABELS.put("total", "Total");
        LABELS.put("department", "Department");
        LABELS.put("lines", "Line items");
        LABELS.put("taxes", "Tax lines");
        LABELS.put("dueDate", "Due date");
        LABELS.put("shipToProvince", "Ship-to province");
    }

    public String code() { return "X03"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        Set<String> low = new LinkedHashSet<>();
        if (i.getLowConfidenceFields() != null) low.addAll(Arrays.asList(i.getLowConfidenceFields().split(",")));
        List<String> missing = new ArrayList<>();
        if (i.getVendorInvoiceNumber() == null) missing.add("invoiceNumber");
        if (i.getInvoiceDate() == null) missing.add("invoiceDate");
        if (i.getTotal() == null) missing.add("total");
        if (i.getDepartmentId() == null) missing.add("department");
        if (i.getVendorNameRaw() == null && i.getVendorId() == null) missing.add("vendor");
        // An unmatched vendor is V01's job, not ours.
        low.remove("vendor");
        low.removeAll(missing);
        List<FlagDraft> out = new ArrayList<>();
        if (!missing.isEmpty()) {
            out.add(flag(String.join(", ", missing.stream().map(LABELS::get).toList()) + " could not be read or inferred; please enter "
                    + (missing.size() == 1 ? "it" : "them") + " before submitting", "missing", missing));
        }
        if (!low.isEmpty()) {
            out.add(flag(String.join(", ", low.stream().map(f -> LABELS.getOrDefault(f, f)).toList())
                    + " read with low confidence; please confirm against the document", "lowConfidence", new ArrayList<>(low)));
        }
        return out;
    }
}
