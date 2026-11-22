package com.brimap.bonus;

import com.brimap.model.VirtualCard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** D-100: card numbers, and the four locks (vendor, amount, single use, expiry) every charge must pass. */
class VirtualCardsTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    static VirtualCard card(String status) {
        VirtualCard c = new VirtualCard();
        c.setId("VC-00417"); c.setPaymentId("PAY-00417"); c.setVendorId("V012"); c.setNetwork("Visa"); c.setLast4("4242");
        c.setCurrency("CAD"); c.setSpendLimit(new BigDecimal("1250.00")); c.setExpiresOn(TODAY.plusDays(30)); c.setStatus(status);
        if (!"ISSUED".equals(status)) c.setChargedAmount(new BigDecimal("1250.00"));
        return c;
    }

    @Test
    void numbersAreSixteenDigitsWithTheIssuerPrefixAndAValidLuhnDigit() {
        Random rnd = new Random(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String n = VirtualCards.newNumber(rnd);
            assertThat(n).hasSize(16).startsWith(VirtualCards.PREFIX);
            assertThat(VirtualCards.luhnValid(n)).isTrue();
            seen.add(n);
        }
        assertThat(seen).hasSizeGreaterThan(495);   // effectively unique
    }

    @Test
    void luhnCatchesATypo() {
        assertThat(VirtualCards.luhnValid("4242424242424242")).isTrue();   // the well-known test number
        assertThat(VirtualCards.luhnValid("4242424242424241")).isFalse();
        assertThat(VirtualCards.luhnValid("42424242abcd4242")).isFalse();
    }

    @Test
    void theVendorCanChargeUpToTheLimitBeforeExpiry() {
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V012", "Trailhead Fuel", new BigDecimal("1250.00"), TODAY)).isEmpty();
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V012", "Trailhead Fuel", new BigDecimal("1000.00"), TODAY.plusDays(30))).isEmpty();
    }

    @Test
    void anotherMerchantIsDeclined() {
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V020", "Some Other Vendor", new BigDecimal("10.00"), TODAY))
                .hasValueSatisfying(r -> assertThat(r).contains("locked to one vendor").contains("Some Other Vendor"));
        assertThat(VirtualCards.declineReason(card("ISSUED"), null, null, new BigDecimal("10.00"), TODAY)).isPresent();
    }

    @Test
    void oneCentOverTheLimitIsDeclined() {
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V012", "Trailhead Fuel", new BigDecimal("1250.01"), TODAY))
                .hasValueSatisfying(r -> assertThat(r).contains("over the card limit"));
    }

    @Test
    void singleUse_aSecondChargeIsDeclined() {
        assertThat(VirtualCards.declineReason(card("CHARGED"), "V012", "Trailhead Fuel", new BigDecimal("1.00"), TODAY))
                .hasValueSatisfying(r -> assertThat(r).contains("Single-use"));
        assertThat(VirtualCards.declineReason(card("CLOSED"), "V012", "Trailhead Fuel", new BigDecimal("1.00"), TODAY)).isPresent();
    }

    @Test
    void expiredOrCancelledCardsAreDeclined() {
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V012", "Trailhead Fuel", new BigDecimal("1.00"), TODAY.plusDays(31)))
                .hasValueSatisfying(r -> assertThat(r).contains("expired"));
        assertThat(VirtualCards.declineReason(card("CANCELLED"), "V012", "Trailhead Fuel", new BigDecimal("1.00"), TODAY))
                .hasValueSatisfying(r -> assertThat(r).contains("cancelled"));
    }

    @Test
    void zeroOrNegativeChargesAreDeclined() {
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V012", "Trailhead Fuel", BigDecimal.ZERO, TODAY)).isPresent();
        assertThat(VirtualCards.declineReason(card("ISSUED"), "V012", "Trailhead Fuel", new BigDecimal("-5"), TODAY)).isPresent();
    }
}
