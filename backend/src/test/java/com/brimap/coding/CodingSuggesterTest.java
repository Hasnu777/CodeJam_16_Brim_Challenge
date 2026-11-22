package com.brimap.coding;

import com.brimap.model.InvoiceLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodingSuggesterTest {
    private static final Map<String, String> GL = Map.of("5010", "Freight - outbound", "5020", "Fleet fuel", "6110", "Software subscriptions",
            "6310", "Legal fees");

    private static InvoiceLine line(String d) {
        InvoiceLine l = new InvoiceLine();
        l.setLineNo(1);
        l.setDescription(d);
        return l;
    }

    @Test
    void vendorHistoryWinsAndReasonShowsCounts() {
        List<CodingSuggester.HistoryLine> h = new ArrayList<>();
        for (int i = 0; i < 7; i++) h.add(new CodingSuggester.HistoryLine("AP-0000" + i, "V001", "LTL shipment", "5010", "D01"));
        h.add(new CodingSuggester.HistoryLine("AP-00009", "V001", "Fuel surcharge", "5020", "D01"));
        var s = CodingSuggester.suggest(line("LTL shipment Toronto-Edmonton"), "V001", "Polar Freight Lines Ltd.", "5010", null, h, GL);
        assertThat(s.glAccount()).isEqualTo("5010");
        assertThat(s.departmentId()).isEqualTo("D01");
        assertThat(s.reason()).startsWith("7 of 8 past invoice lines from Polar Freight Lines Ltd. (8 invoices) went to 5010 Freight - outbound");
        assertThat(s.source()).isEqualTo("VENDOR_HISTORY");
    }

    @Test
    void unknownVendorFallsBackToSimilarDescriptions_blueHeronCourier() {
        List<CodingSuggester.HistoryLine> h = List.of(
                new CodingSuggester.HistoryLine("AP-00012", "V006", "LTL shipment Vancouver-Calgary", "5010", "D01"),
                new CodingSuggester.HistoryLine("AP-00013", "V015", "Cloudbarn Workspace subscription", "6110", "D05"));
        var s = CodingSuggester.suggest(line("LTL shipment Vancouver-Calgary"), null, "Blue Heron Couriers", null, null, h, GL);
        assertThat(s.glAccount()).isEqualTo("5010");
        assertThat(s.source()).isEqualTo("SIMILAR_LINES");
        assertThat(s.reason()).contains("No history with this vendor").contains("AP-00012");
    }

    @Test
    void vendorDefaultThenNothing() {
        var s = CodingSuggester.suggest(line("Retainer"), "V052", "Harlow and Stern LLP", "6310", "D04", List.of(), GL);
        assertThat(s.glAccount()).isEqualTo("6310");
        assertThat(s.departmentId()).isEqualTo("D04");
        assertThat(s.reason()).isEqualTo("No history; vendor's default account is 6310 Legal fees");
        assertThat(CodingSuggester.suggest(line("x"), null, "?", null, null, List.of(), GL).glAccount()).isNull();
    }
}
