package com.brimap.bonus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Bonus 5 - Brim card payment optimizer. For a bill from a vendor that accepts card, paying with the AP card on the
 * scheduled date instead of EFT keeps the cash in the bank until the card statement is due, and earns card rewards.
 * Pure function, no I/O: the same inputs always give the same advice (see CardPaymentAdvisorTest).
 *
 * <p>The card terms are assumptions, not dataset facts (D-085): the statement closes on the last day of the month and
 * is due {@code graceDays} later; cash is valued at {@code costOfCashAnnual}; rewards at {@code rewardsRate}.
 */
public final class CardPaymentAdvisor {
    private CardPaymentAdvisor() {}

    public record Terms(int graceDays, BigDecimal rewardsRate, BigDecimal costOfCashAnnual, BigDecimal perTxnLimitCad) {
        public static final Terms DEFAULT = new Terms(21, new BigDecimal("0.01"), new BigDecimal("0.05"), new BigDecimal("50000.00"));
    }

    public enum Verdict { SWITCH, ON_CARD, KEEP, NOT_ELIGIBLE }

    public record Input(boolean vendorAcceptsCard, String currentMethod, String currency, BigDecimal amountCad,
                        LocalDate chargeDate, BigDecimal discountTaken) {}

    public record Advice(Verdict verdict, String headline, LocalDate chargeDate, LocalDate statementClose, LocalDate cardDueDate,
                         int floatDays, BigDecimal floatValueCad, BigDecimal rewardsCad, BigDecimal benefitCad, List<String> notes) {
        public boolean benefits() { return verdict == Verdict.SWITCH || verdict == Verdict.ON_CARD; }
    }

    public static Advice advise(Input in, Terms t) {
        BigDecimal amt = in.amountCad() == null ? BigDecimal.ZERO : in.amountCad();
        if (!in.vendorAcceptsCard()) return none(Verdict.NOT_ELIGIBLE, "Vendor does not accept card payments", in.chargeDate());
        if (in.currency() != null && !"CAD".equalsIgnoreCase(in.currency())) {
            return none(Verdict.KEEP, in.currency() + " bill: card FX conversion fees would likely cost more than the float, so no card recommendation", in.chargeDate());
        }
        if (amt.compareTo(t.perTxnLimitCad()) > 0) {
            return none(Verdict.KEEP, "Keep " + method(in) + ": above the $" + t.perTxnLimitCad().setScale(0, RoundingMode.DOWN).toPlainString()
                    + " card transaction limit", in.chargeDate());
        }
        if (amt.signum() <= 0 || in.chargeDate() == null) return none(Verdict.KEEP, "Nothing to charge", in.chargeDate());

        LocalDate close = in.chargeDate().withDayOfMonth(in.chargeDate().lengthOfMonth());
        LocalDate due = close.plusDays(t.graceDays());
        int days = (int) ChronoUnit.DAYS.between(in.chargeDate(), due);
        BigDecimal floatValue = amt.multiply(t.costOfCashAnnual()).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        BigDecimal rewards = amt.multiply(t.rewardsRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal benefit = floatValue.add(rewards);

        List<String> notes = new ArrayList<>();
        notes.add("Charged " + in.chargeDate() + ", statement closes " + close + ", card due " + due + " (" + days + " days)");
        notes.add("Cash kept " + days + " days at " + pct(t.costOfCashAnnual()) + "/yr = $" + floatValue.toPlainString()
                + "; rewards " + pct(t.rewardsRate()) + " = $" + rewards.toPlainString());
        if (in.discountTaken() != null && in.discountTaken().signum() > 0) {
            notes.add("Charged on the discount date, so the early-pay discount ($" + in.discountTaken().toPlainString() + ") is kept");
        }
        boolean onCard = "CARD".equalsIgnoreCase(in.currentMethod());
        String headline = (onCard ? "On Brim card: " : "Pay by Brim card: ") + "cash stays " + days + " more days, ~$"
                + benefit.toPlainString() + " in float and rewards";
        return new Advice(onCard ? Verdict.ON_CARD : Verdict.SWITCH, headline, in.chargeDate(), close, due, days, floatValue, rewards, benefit, notes);
    }

    /** Totals for a set of advice, e.g. "what EFT payments to card-accepting vendors left on the table". */
    public record Summary(int count, BigDecimal amountCad, BigDecimal benefitCad, int avgFloatDays) {}

    public static Summary summarize(List<Advice> advice, List<BigDecimal> amountsCad) {
        BigDecimal amount = BigDecimal.ZERO, benefit = BigDecimal.ZERO;
        long days = 0;
        int n = 0;
        for (int i = 0; i < advice.size(); i++) {
            Advice a = advice.get(i);
            if (!a.benefits()) continue;
            n++;
            amount = amount.add(amountsCad.get(i) == null ? BigDecimal.ZERO : amountsCad.get(i));
            benefit = benefit.add(a.benefitCad());
            days += a.floatDays();
        }
        return new Summary(n, amount, benefit, n == 0 ? 0 : (int) Math.round((double) days / n));
    }

    private static Advice none(Verdict v, String headline, LocalDate charge) {
        return new Advice(v, headline, charge, null, null, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }

    private static String method(Input in) { return in.currentMethod() == null ? "EFT" : in.currentMethod(); }

    private static String pct(BigDecimal rate) { return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%"; }
}
