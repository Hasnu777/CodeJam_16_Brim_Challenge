package com.brimap.bonus;

import com.brimap.bonus.SpendAggregator.GroupBy;
import com.brimap.bonus.SpendAggregator.Reference;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceLine;
import com.brimap.model.InvoiceStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpendAggregatorTest {
    static Invoice inv(String id, String vendor, String dept, String date, String totalCad, InvoiceStatus s) {
        Invoice i = new Invoice();
        i.setId(id); i.setVendorId(vendor); i.setDepartmentId(dept); i.setInvoiceDate(LocalDate.parse(date));
        i.setTotalCad(new BigDecimal(totalCad)); i.setStatus(s);
        return i;
    }

    static InvoiceLine line(String invoiceId, String amount, String gl, String dept) {
        InvoiceLine l = new InvoiceLine();
        l.setInvoiceId(invoiceId); l.setAmount(new BigDecimal(amount)); l.setGlAccount(gl); l.setDepartmentId(dept);
        return l;
    }

    static final Reference REF = new Reference(
            Map.of("V001", "Polar Freight", "V002", "Great Lakes", "V003", "Cloudly", "D01", "Operations", "D02", "IT", "5010", "Freight"),
            Map.of("V001", "Freight & Carriers", "V002", "Freight & Carriers", "V003", "Software & SaaS"));

    final List<Invoice> invoices = List.of(
            inv("A", "V001", "D01", "2026-09-10", "1130.00", InvoiceStatus.PAID),
            inv("B", "V002", "D01", "2026-10-02", "565.00", InvoiceStatus.APPROVED),
            inv("C", "V003", "D02", "2026-10-15", "2260.00", InvoiceStatus.SCHEDULED),
            inv("D", "V003", "D02", "2026-10-20", "9999.00", InvoiceStatus.PENDING_APPROVAL), // not spent yet
            inv("E", "V001", "D01", "2026-10-21", "777.00", InvoiceStatus.REJECTED));         // never spent

    @Test
    void category_groupsVendorsTogether_andIgnoresUnapprovedBills() {
        var r = SpendAggregator.aggregate(GroupBy.CATEGORY, invoices, List.of(), REF, null, null, null);
        assertThat(r.totalCad()).isEqualByComparingTo("3955.00");
        assertThat(r.invoiceCount()).isEqualTo(3);
        assertThat(r.rows()).extracting(SpendAggregator.Row::key).containsExactly("Software & SaaS", "Freight & Carriers");
        var freight = r.rows().get(1);
        assertThat(freight.amountCad()).isEqualByComparingTo("1695.00");
        assertThat(freight.invoices()).isEqualTo(2);
        assertThat(freight.share()).isEqualByComparingTo("42.9");
    }

    @Test
    void vendor_usesLegalNameLabels_andUnknownVendorBucket() {
        var withUnknown = new java.util.ArrayList<>(invoices);
        withUnknown.add(inv("F", null, "D01", "2026-10-01", "100.00", InvoiceStatus.PAID));
        var r = SpendAggregator.aggregate(GroupBy.VENDOR, withUnknown, List.of(), REF, null, null, null);
        assertThat(r.rows()).extracting(SpendAggregator.Row::label).containsExactly("Cloudly", "Polar Freight", "Great Lakes", "unknown");
    }

    @Test
    void unknownVendorOrMissingCategory_isUncategorized() {
        var list = List.of(inv("X", "V999", "D01", "2026-10-01", "50.00", InvoiceStatus.PAID), inv("Y", null, "D01", "2026-10-01", "25.00", InvoiceStatus.PAID));
        var r = SpendAggregator.aggregate(GroupBy.CATEGORY, list, List.of(), REF, null, null, null);
        assertThat(r.rows()).singleElement().satisfies(row -> {
            assertThat(row.key()).isEqualTo("Uncategorized");
            assertThat(row.amountCad()).isEqualByComparingTo("75.00");
        });
    }

    @Test
    void dept_usesPreTaxLinesConvertedToCad_withLineDepartmentOverride() {
        Invoice usd = inv("U", "V003", "D02", "2026-10-05", "1356.00", InvoiceStatus.PAID);
        usd.setFxRateToCad(new BigDecimal("1.3560"));
        var lines = List.of(line("A", "1000.00", "5010", null), line("C", "1500.00", "6100", "D02"), line("C", "500.00", "6100", "D01"),
                line("U", "1000.00", "6100", null), line("D", "9000.00", "6100", "D02"));
        var r = SpendAggregator.aggregate(GroupBy.DEPT, List.of(invoices.get(0), invoices.get(2), invoices.get(3), usd), lines, REF, null, null, null);
        assertThat(r.rows()).extracting(SpendAggregator.Row::key).containsExactly("D02", "D01");
        assertThat(r.rows().get(0).amountCad()).isEqualByComparingTo("2856.00"); // 1500 + 1000 USD * 1.356
        assertThat(r.rows().get(0).label()).isEqualTo("IT");
        assertThat(r.rows().get(1).amountCad()).isEqualByComparingTo("1500.00"); // 1000 + 500 charged from C
        assertThat(r.totalCad()).isEqualByComparingTo("4356.00");
    }

    @Test
    void gl_uncodedLinesGetTheirOwnBucket() {
        var lines = List.of(line("A", "1000.00", "5010", null), line("B", "500.00", null, null));
        var r = SpendAggregator.aggregate(GroupBy.GL, invoices, lines, REF, null, null, null);
        assertThat(r.rows()).extracting(SpendAggregator.Row::key).containsExactly("5010", "uncoded");
        assertThat(r.rows().get(0).label()).isEqualTo("Freight");
    }

    @Test
    void approverScope_onlyTheirDepartment_includingTheirShareOfSplitBills() {
        var lines = List.of(line("C", "1500.00", "6100", "D02"), line("C", "500.00", "6100", "D01"), line("A", "1000.00", "5010", null));
        var byDept = SpendAggregator.aggregate(GroupBy.DEPT, invoices, lines, REF, null, null, "D02");
        assertThat(byDept.scope()).isEqualTo("D02");
        assertThat(byDept.rows()).extracting(SpendAggregator.Row::key).containsExactly("D02");
        assertThat(byDept.totalCad()).isEqualByComparingTo("1500.00");

        var byCategory = SpendAggregator.aggregate(GroupBy.CATEGORY, invoices, List.of(), REF, null, null, "D01");
        assertThat(byCategory.rows()).extracting(SpendAggregator.Row::key).containsExactly("Freight & Carriers");
    }

    @Test
    void dateRange_isInclusive_andMonthlyTrendAddsUpToTotal() {
        var r = SpendAggregator.aggregate(GroupBy.VENDOR, invoices, List.of(), REF, LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-15"), null);
        assertThat(r.invoiceCount()).isEqualTo(2);
        assertThat(r.totalCad()).isEqualByComparingTo("2825.00");
        assertThat(r.monthly()).containsOnlyKeys("2026-10");
        assertThat(r.monthly().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(r.totalCad());
    }

    @Test
    void empty_hasZeroTotalAndNoRows() {
        var r = SpendAggregator.aggregate(GroupBy.CATEGORY, List.of(), List.of(), REF, null, null, null);
        assertThat(r.totalCad()).isEqualByComparingTo("0");
        assertThat(r.rows()).isEmpty();
    }

    @Test
    void badInput_isRejected() {
        assertThatThrownBy(() -> GroupBy.parse("colour")).isInstanceOf(IllegalArgumentException.class);
        assertThat(GroupBy.parse("Category")).isEqualTo(GroupBy.CATEGORY);
        assertThatThrownBy(() -> SpendAggregator.aggregate(GroupBy.VENDOR, invoices, List.of(), REF, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-10-01"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
