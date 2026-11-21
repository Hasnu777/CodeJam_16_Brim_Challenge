package com.brimap.payment;

import com.brimap.model.*;
import com.brimap.seed.PolicySeed;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P09/P10/P11 and the payment holds, on the PRD's worked example: Elise approved as Liam's delegate, Marcus approved step 2. */
class ReleaseRulesTest {
    static final List<ApprovalPolicy> POLICIES = PolicySeed.build(List.of());

    static AppUser user(String id, Role role) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setFullName(id);
        return u;
    }

    static Payment payment(String cad) {
        Payment p = new Payment();
        p.setId("PAY-00416"); p.setStatus("SCHEDULED"); p.setMethod("EFT"); p.setAmountCad(new BigDecimal(cad));
        return p;
    }

    static ApprovalStep step(String actedBy, String onBehalfOf) {
        ApprovalStep s = new ApprovalStep();
        s.setDecision("APPROVED"); s.setActedBy(actedBy); s.setActedOnBehalfOf(onBehalfOf);
        return s;
    }

    static Vendor vendor(String status) {
        Vendor v = new Vendor();
        v.setId("V036"); v.setLegalName("Queen Street Properties Inc."); v.setStatus(status);
        return v;
    }

    static VendorBankAccount account(boolean verified) {
        VendorBankAccount a = new VendorBankAccount();
        a.setId(1L); a.setLast4("1421"); a.setStatus("ACTIVE"); a.setVerified(verified);
        return a;
    }

    static List<String> blockers(AppUser who, String cad, List<ApprovalStep> steps, Vendor v, VendorBankAccount a, List<Flag> flags) {
        Invoice inv = new Invoice();
        inv.setId("AP-00500"); inv.setRequesterUserId("U037");
        return ReleaseRules.blockers(new ReleaseRules.Input(payment(cad), inv, who, steps, v, a, flags, POLICIES, List.of()));
    }

    final List<ApprovalStep> chain = List.of(step("U003", "U012"), step("U002", null));

    @Test
    void workedExample_onlyTheCfoCanRelease() {
        assertThat(blockers(user("U003", Role.FM), "12000", chain, vendor("active"), account(true), List.of()))
                .anyMatch(b -> b.startsWith("You approved this invoice (as delegate for U012)"));
        assertThat(blockers(user("U002", Role.ADM), "12000", chain, vendor("active"), account(true), List.of()))
                .anyMatch(b -> b.startsWith("You approved this invoice"));
        assertThat(blockers(user("U012", Role.APR), "12000", chain, vendor("active"), account(true), List.of()))
                .anyMatch(b -> b.contains("Your delegate approved this invoice on your behalf"));
        assertThat(blockers(user("U001", Role.ADM), "12000", chain, vendor("active"), account(true), List.of())).isEmpty();
    }

    @Test
    void p09_p10_financeManagerUpTo100k_controllerAbove() {
        assertThat(blockers(user("U003", Role.FM), "100000", List.of(), vendor("active"), account(true), List.of())).isEmpty();
        assertThat(blockers(user("U003", Role.FM), "100000.01", List.of(), vendor("active"), account(true), List.of()))
                .anyMatch(b -> b.startsWith("Over your release limit"));
        assertThat(blockers(user("U002", Role.ADM), "150000", List.of(), vendor("active"), account(true), List.of())).isEmpty();
    }

    @Test
    void clerkPreparesButNeverReleases() {
        assertThat(blockers(user("U004", Role.CLK), "500", List.of(), vendor("active"), account(true), List.of()))
                .anyMatch(b -> b.contains("AP clerks prepare payments"));
    }

    @Test
    void holds_unapprovedVendor_unverifiedBank_openBlockFlag() {
        Flag f = new Flag();
        f.setRuleCode("B02"); f.setSeverity(Severity.BLOCK); f.setStatus("OPEN");
        var out = blockers(user("U001", Role.ADM), "6051.23", List.of(), vendor("pending_review"), account(false), List.of(f));
        assertThat(out).anyMatch(b -> b.contains("pending_review")).anyMatch(b -> b.contains("not verified")).anyMatch(b -> b.contains("B02"));
    }
}
