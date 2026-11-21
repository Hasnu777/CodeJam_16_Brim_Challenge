package com.brimap.validation.rules;

import com.brimap.extraction.VendorMatcher;
import com.brimap.model.Invoice;
import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V03: GST/QST number printed on the invoice differs from the vendor record, or is badly formatted
 * (GST \d{9}RT\d{4}, QST \d{10}TQ\d{4}).
 */
@Component
public class V03TaxNumberMismatch implements ValidationRule {
    public String code() { return "V03"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        Vendor v = ctx.vendor();
        List<FlagDraft> out = new ArrayList<>();
        String gst = i.getGstNumberOnInvoice();
        if (gst != null && !gst.matches("\\d{9}RT\\d{4}")) {
            out.add(flag("GST/HST number on the invoice (" + gst + ") is not in the valid format 123456789RT0001", "gstOnInvoice", gst));
        } else if (gst != null && v != null && v.getGstHstNumber() != null && !gst.equals(VendorMatcher.canonicalGst(v.getGstHstNumber()))) {
            out.add(flag("GST/HST number on the invoice (" + gst + ") differs from " + v.getLegalName() + "'s record (" + v.getGstHstNumber() + ")",
                    "gstOnInvoice", gst, "gstOnFile", v.getGstHstNumber()));
        }
        String qst = i.getQstNumberOnInvoice();
        if (qst != null && !qst.matches("\\d{10}TQ\\d{4}")) {
            out.add(flag("QST number on the invoice (" + qst + ") is not in the valid format 1234567890TQ0001", "qstOnInvoice", qst));
        } else if (qst != null && v != null && v.getQstNumber() != null && !qst.equals(v.getQstNumber())) {
            out.add(flag("QST number on the invoice (" + qst + ") differs from the vendor record (" + v.getQstNumber() + ")",
                    "qstOnInvoice", qst, "qstOnFile", v.getQstNumber()));
        }
        return out;
    }
}
