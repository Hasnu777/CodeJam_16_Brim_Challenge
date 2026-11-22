package com.brimap.validation;

import com.brimap.model.Invoice;
import com.brimap.validation.rules.V06LookalikeSenderDomain;
import org.junit.jupiter.api.Test;

import static com.brimap.validation.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class FraudBonusRulesTest {
    @Test
    void v06_harlowAndSternEmailDomainResemblesTheRealHarlowDomain() {
        Invoice inv = invoice("AP-T1", "V052", "HA26-2766", "2026-10-27", "5710.00", "6452.30");
        inv.setSenderEmail("billing@harlowandstern-llp.example");
        var c = ctx(inv, harlowLookalike()).anyVendor(harlow()).anyVendor(polar())
                .otherInvoice(history("AP-00100", "V026", "001400", "2026-05-01", "1000", "1130", com.brimap.model.InvoiceStatus.PAID)).build();
        assertThat(new V06LookalikeSenderDomain().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("harlowandstern-llp.example looks like Harlow & Stern LLP's domain harlow.example"));
        // The genuine Harlow & Stern mail (from harlow.example) is not flagged because a lookalike record exists.
        Invoice real = invoice("AP-T9", "V026", "001468", "2026-10-20", "1000", "1130");
        real.setSenderEmail("ar@harlow.example");
        var c2 = ctx(real, harlow()).anyVendor(harlowLookalike())
                .otherInvoice(history("AP-00100", "V026", "001400", "2026-05-01", "1000", "1130", com.brimap.model.InvoiceStatus.PAID)).build();
        assertThat(new V06LookalikeSenderDomain().check(c2)).isEmpty();
    }

    @Test
    void v06_typosquatOfAKnownVendor_butNotTheVendorsOwnDomain() {
        Invoice inv = invoice("AP-T2", "V004", "1625", "2026-10-27", "1", "1");
        inv.setSenderEmail("ar@cascadia-cartage.example");
        var vendor = cascadia();
        // matched vendor is someone else (e.g. sender pretending to be Cascadia)
        var c = ctx(invoice("AP-T3", "V001", "9", "2026-10-27", "1", "1"), polar()).anyVendor(vendor).build();
        c.invoice().setSenderEmail("ar@cascadia-cartage.example");
        assertThat(new V06LookalikeSenderDomain().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("cascadiacartage.example"));
        inv.setSenderEmail("ar@cascadiacartage.example");
        assertThat(new V06LookalikeSenderDomain().check(ctx(inv, vendor).build())).isEmpty();
    }
}
