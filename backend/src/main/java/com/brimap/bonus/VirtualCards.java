package com.brimap.bonus;

import com.brimap.model.VirtualCard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Brim virtual cards (D-100), as pure functions: card numbers and the authorization rules a charge must pass.
 * A card is issued per card payment and is locked four ways: to one vendor, to the payment amount, to one use, and to
 * an expiry date. Any other charge is declined, which is what makes a leaked card number worthless.
 */
public final class VirtualCards {
    private VirtualCards() {}

    /** Fictional issuer prefix for the simulation (not a real BIN). */
    public static final String PREFIX = "455921";
    public static final int VALID_DAYS = 30;

    /** A 16-digit number with a valid Luhn check digit. */
    public static String newNumber(RandomGenerator rnd) {
        StringBuilder sb = new StringBuilder(PREFIX);
        while (sb.length() < 15) sb.append(rnd.nextInt(10));
        sb.append(checkDigit(sb.toString()));
        return sb.toString();
    }

    public static boolean luhnValid(String number) {
        if (number == null || !number.matches("\\d{12,19}")) return false;
        return checkDigit(number.substring(0, number.length() - 1)) == number.charAt(number.length() - 1) - '0';
    }

    static int checkDigit(String body) {
        int sum = 0;
        boolean dbl = true;   // the digit left of the check digit is doubled
        for (int i = body.length() - 1; i >= 0; i--) {
            int d = body.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** Empty = approved; otherwise the decline reason shown to the user and written to the audit log. */
    public static Optional<String> declineReason(VirtualCard card, String merchantVendorId, String merchantName, BigDecimal amount, LocalDate on) {
        switch (card.getStatus() == null ? "" : card.getStatus()) {
            case "ISSUED" -> { }
            case "CHARGED", "CLOSED" -> { return Optional.of("Single-use card already charged " + card.getChargedAmount().toPlainString() + " " + card.getCurrency()); }
            case "CANCELLED" -> { return Optional.of("Card was cancelled"); }
            default -> { return Optional.of("Card is " + card.getStatus()); }
        }
        if (merchantVendorId == null || !merchantVendorId.equals(card.getVendorId())) {
            return Optional.of("Card is locked to one vendor; a charge from " + (merchantName == null ? "another merchant" : merchantName) + " is declined");
        }
        if (amount == null || amount.signum() <= 0) return Optional.of("Charge amount must be positive");
        if (amount.compareTo(card.getSpendLimit()) > 0) {
            return Optional.of("Charge " + amount.toPlainString() + " is over the card limit " + card.getSpendLimit().toPlainString() + " " + card.getCurrency());
        }
        if (on.isAfter(card.getExpiresOn())) return Optional.of("Card expired on " + card.getExpiresOn());
        return Optional.empty();
    }
}
