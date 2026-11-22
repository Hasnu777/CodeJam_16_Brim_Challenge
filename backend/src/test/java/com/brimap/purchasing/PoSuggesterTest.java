package com.brimap.purchasing;

import com.brimap.model.Invoice;
import com.brimap.model.PurchaseOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.brimap.validation.Fixtures.invoice;
import static org.assertj.core.api.Assertions.assertThat;

class PoSuggesterTest {

    static PurchaseOrder po(String id, String vendor, String dept, String issued, String total, String status) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id); p.setVendorId(vendor); p.setDepartmentId(dept); p.setIssueDate(LocalDate.parse(issued));
        p.setTotalAmount(new BigDecimal(total)); p.setCurrency("CAD"); p.setStatus(status); p.setDescription("Test PO " + id);
        return p;
    }

    @Test
    void onlySameVendorOpenOrPartialPosAreCandidates() {
        Invoice inv = invoice("AP-1", "V046", "K-100", "2026-10-20", "1000.00", "1130.00");
        var out = PoSuggester.rank(inv, List.of(
                po("PO-A", "V046", "D01", "2026-09-01", "5000.00", "open"),
                po("PO-B", "V046", "D01", "2026-08-01", "5000.00", "closed"),
                po("PO-C", "V099", "D01", "2026-09-01", "5000.00", "open"),
                po("PO-D", "V046", "D01", "2026-07-01", "5000.00", "partially_invoiced")), Map.of());
        assertThat(out).extracting(PoSuggester.Suggestion::poId).containsExactly("PO-A", "PO-D");
        assertThat(out.get(0).bestMatch()).isTrue();
        assertThat(out.get(1).bestMatch()).isFalse();
        assertThat(out.get(0).reasons()).anyMatch(r -> r.contains("49 days before the invoice"));
    }

    @Test
    void remainingBalanceUsesWhatOtherInvoicesAlreadyBilled_torqueStyle() {
        Invoice inv = invoice("AP-2", "V044", "1800", "2026-10-27", "3000.00", "3390.00");
        var out = PoSuggester.rank(inv, List.of(po("PO-2026-0037", "V044", "D03", "2026-06-01", "10000.00", "partially_invoiced")),
                Map.of("PO-2026-0037", new BigDecimal("8000.00")));
        var s = out.get(0);
        assertThat(s.remaining()).isEqualByComparingTo("2000.00");
        assertThat(s.bestMatch()).isFalse();
        assertThat(s.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("Only $2,000.00 remaining").contains("over by $1,000.00").contains("Finance Manager"));
    }

    @Test
    void poIssuedAfterTheInvoiceIsWarnedAndRankedBelowOneIssuedBefore() {
        Invoice inv = invoice("AP-3", "V043", "F-1", "2026-10-01", "500.00", "565.00");
        var out = PoSuggester.rank(inv, List.of(
                po("PO-LATE", "V043", "D01", "2026-10-15", "5000.00", "open"),
                po("PO-EARLY", "V043", "D01", "2026-03-01", "5000.00", "open")), Map.of());
        assertThat(out).extracting(PoSuggester.Suggestion::poId).containsExactly("PO-EARLY", "PO-LATE");
        assertThat(out.get(1).warnings()).singleElement().satisfies(w -> assertThat(w).isEqualTo("Issued 14 days after the invoice date"));
    }

    @Test
    void amongCleanMatches_sameDepartmentThenClosestIssueDateWins() {
        Invoice inv = invoice("AP-4", "V011", "S-9", "2026-10-20", "100.00", "113.00");
        inv.setDepartmentId("D07");
        var out = PoSuggester.rank(inv, List.of(
                po("PO-OTHER-DEPT-RECENT", "V011", "D01", "2026-10-19", "5000.00", "open"),
                po("PO-SAME-DEPT-OLD", "V011", "D07", "2026-05-01", "5000.00", "open"),
                po("PO-SAME-DEPT-RECENT", "V011", "D07", "2026-10-01", "5000.00", "open")), Map.of());
        assertThat(out).extracting(PoSuggester.Suggestion::poId)
                .containsExactly("PO-SAME-DEPT-RECENT", "PO-SAME-DEPT-OLD", "PO-OTHER-DEPT-RECENT");
        assertThat(out.get(0).reasons()).contains("Same department: D07");
    }

    @Test
    void currencyMismatchIsAWarning() {
        Invoice inv = invoice("AP-5", "V020", "U-1", "2026-10-20", "100.00", "100.00");
        inv.setCurrency("USD");
        var out = PoSuggester.rank(inv, List.of(po("PO-X", "V020", "D01", "2026-09-01", "5000.00", "open")), Map.of());
        assertThat(out.get(0).warnings()).containsExactly("PO is in CAD, invoice is in USD");
        assertThat(out.get(0).bestMatch()).isFalse();
    }

    @Test
    void noVendorOrNoOpenPo_givesNoSuggestions_brightlineStyle() {
        Invoice unknownVendor = invoice("AP-6", null, "X", "2026-10-20", "100.00", "113.00");
        assertThat(PoSuggester.rank(unknownVendor, List.of(po("PO-X", "V013", "D07", "2026-09-01", "5000.00", "open")), Map.of())).isEmpty();
        Invoice brightline = invoice("AP-7", "V013", "B-1", "2026-10-20", "100.00", "113.00");
        assertThat(PoSuggester.rank(brightline, List.of(po("PO-2026-0003", "V013", "D07", "2025-12-25", "2837.54", "closed")), Map.of())).isEmpty();
    }
}
