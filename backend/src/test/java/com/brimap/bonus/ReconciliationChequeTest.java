package com.brimap.bonus;

import com.brimap.model.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** D-105: identical monthly rent cheques ($6,990.48) match the cheque written most recently before the clearing date. */
class ReconciliationChequeTest {
    static final BigDecimal RENT = new BigDecimal("6990.48");

    static Payment cheque(String id, String scheduled, String written) {
        Payment p = new Payment();
        p.setId(id);
        p.setMethod("CHEQUE");
        p.setAmount(RENT);
        p.setScheduledDate(LocalDate.parse(scheduled));
        if (written != null) p.setPaidDate(LocalDate.parse(written));
        return p;
    }

    static Optional<String> match(List<Payment> pays, String posted, Set<String> used) {
        return ReconciliationService.nearestCheque(pays, RENT, LocalDate.parse(posted), used).map(Payment::getId);
    }

    @Test
    void octoberLine_matchesOctoberCheque_notSeptember_inAnyOrder() {
        // Dataset: September's cheque (written Sep 5) never cleared; October's (written Oct 6) cleared Oct 16.
        List<Payment> pays = List.of(cheque("PAY-00347", "2026-09-01", "2026-09-05"), cheque("PAY-00389", "2026-10-01", "2026-10-06"));
        assertThat(match(pays, "2026-10-16", Set.of())).contains("PAY-00389");
        assertThat(match(pays.reversed(), "2026-10-16", Set.of())).contains("PAY-00389");
    }

    @Test
    void chequeCannotClearBeforeItIsWritten() {
        // Jan 17 is 15 days before February's cheque but 11 days after January's was written: January's wins.
        List<Payment> pays = List.of(cheque("PAY-00005", "2026-01-01", "2026-01-06"), cheque("PAY-00039", "2026-02-01", "2026-02-04"));
        assertThat(match(pays, "2026-01-17", Set.of())).contains("PAY-00005");
    }

    @Test
    void skipsUsedPayments_otherAmounts_andOutOfWindow() {
        Payment other = cheque("PAY-00001", "2026-10-01", null);
        other.setAmount(new BigDecimal("100.00"));
        List<Payment> pays = List.of(other, cheque("PAY-00002", "2026-10-01", null), cheque("PAY-00003", "2026-06-01", null));
        assertThat(match(pays, "2026-10-16", Set.of("PAY-00002"))).isEmpty();
    }

    @Test
    void chequeClearedForADifferentAmount_isFoundWithinTolerance_only() {
        // Dataset: CHEQUE 5018 cleared Sep 13 for 6990.53; September's rent cheque was written Sep 5 for 6990.48.
        List<Payment> pays = List.of(cheque("PAY-00347", "2026-09-01", "2026-09-05"));
        BigDecimal cleared = new BigDecimal("6990.53");
        LocalDate sep13 = LocalDate.parse("2026-09-13");
        assertThat(ReconciliationService.nearestCheque(pays, cleared, sep13, Set.of())).isEmpty(); // exact pass misses it
        assertThat(ReconciliationService.nearestCheque(pays, cleared, sep13, Set.of(), ReconciliationService.CHEQUE_TOLERANCE))
                .map(Payment::getId).contains("PAY-00347");
        assertThat(ReconciliationService.nearestCheque(pays, new BigDecimal("6992.00"), sep13, Set.of(), ReconciliationService.CHEQUE_TOLERANCE)).isEmpty();
    }

    @Test
    void tieOnWait_takesLowestId() {
        List<Payment> pays = List.of(cheque("PAY-00020", "2026-10-01", null), cheque("PAY-00010", "2026-10-01", null));
        assertThat(match(pays, "2026-10-16", Set.of())).contains("PAY-00010");
    }
}
