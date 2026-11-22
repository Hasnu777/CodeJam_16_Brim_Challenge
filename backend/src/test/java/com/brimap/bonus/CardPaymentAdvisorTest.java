package com.brimap.bonus;

import com.brimap.bonus.CardPaymentAdvisor.Input;
import com.brimap.bonus.CardPaymentAdvisor.Terms;
import com.brimap.bonus.CardPaymentAdvisor.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardPaymentAdvisorTest {
    static final Terms T = Terms.DEFAULT; // 21-day grace, 1% rewards, 5%/yr cash, $50k limit

    static Input in(boolean accepts, String method, String currency, String amount, String date, String discount) {
        return new Input(accepts, method, currency, new BigDecimal(amount), LocalDate.parse(date), discount == null ? null : new BigDecimal(discount));
    }

    @Test
    void eftToCardAcceptingVendor_recommendsSwitch_withFloatToStatementDue() {
        var a = CardPaymentAdvisor.advise(in(true, "EFT", "CAD", "10000.00", "2026-11-10", null), T);
        assertThat(a.verdict()).isEqualTo(Verdict.SWITCH);
        assertThat(a.statementClose()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(a.cardDueDate()).isEqualTo(LocalDate.parse("2026-12-21"));
        assertThat(a.floatDays()).isEqualTo(41);
        assertThat(a.floatValueCad()).isEqualByComparingTo("56.16"); // 10000 * 5% * 41/365
        assertThat(a.rewardsCad()).isEqualByComparingTo("100.00");
        assertThat(a.benefitCad()).isEqualByComparingTo("156.16");
        assertThat(a.headline()).isEqualTo("Pay by Brim card: cash stays 41 more days, ~$156.16 in float and rewards");
    }

    @Test
    void chargeOnLastDayOfMonth_stillGetsTheGracePeriod() {
        var a = CardPaymentAdvisor.advise(in(true, "EFT", "CAD", "365.00", "2026-02-28", null), T);
        assertThat(a.floatDays()).isEqualTo(21);
        assertThat(a.cardDueDate()).isEqualTo(LocalDate.parse("2026-03-21"));
    }

    @Test
    void alreadyOnCard_isReportedNotSwitched() {
        var a = CardPaymentAdvisor.advise(in(true, "CARD", "CAD", "1000.00", "2026-11-10", null), T);
        assertThat(a.verdict()).isEqualTo(Verdict.ON_CARD);
        assertThat(a.headline()).startsWith("On Brim card:");
    }

    @Test
    void earlyPayDiscount_isKeptAndNoted() {
        var a = CardPaymentAdvisor.advise(in(true, "EFT", "CAD", "980.00", "2026-11-12", "20.00"), T);
        assertThat(a.verdict()).isEqualTo(Verdict.SWITCH);
        assertThat(a.notes()).anyMatch(n -> n.contains("early-pay discount ($20.00) is kept"));
    }

    @Test
    void vendorWithoutCard_isNotEligible() {
        var a = CardPaymentAdvisor.advise(in(false, "EFT", "CAD", "1000.00", "2026-11-10", null), T);
        assertThat(a.verdict()).isEqualTo(Verdict.NOT_ELIGIBLE);
        assertThat(a.benefitCad()).isEqualByComparingTo("0");
    }

    @Test
    void foreignCurrencyOrOverLimit_keepsEft() {
        assertThat(CardPaymentAdvisor.advise(in(true, "EFT", "USD", "1000.00", "2026-11-10", null), T).verdict()).isEqualTo(Verdict.KEEP);
        var big = CardPaymentAdvisor.advise(in(true, "EFT", "CAD", "50000.01", "2026-11-10", null), T);
        assertThat(big.verdict()).isEqualTo(Verdict.KEEP);
        assertThat(big.headline()).contains("$50000 card transaction limit");
    }

    @Test
    void summarize_countsOnlyPaymentsThatBenefit() {
        var yes = CardPaymentAdvisor.advise(in(true, "EFT", "CAD", "10000.00", "2026-11-10", null), T);
        var no = CardPaymentAdvisor.advise(in(false, "EFT", "CAD", "5000.00", "2026-11-10", null), T);
        var s = CardPaymentAdvisor.summarize(List.of(yes, no), List.of(new BigDecimal("10000.00"), new BigDecimal("5000.00")));
        assertThat(s.count()).isEqualTo(1);
        assertThat(s.amountCad()).isEqualByComparingTo("10000.00");
        assertThat(s.benefitCad()).isEqualByComparingTo("156.16");
        assertThat(s.avgFloatDays()).isEqualTo(41);
    }
}
