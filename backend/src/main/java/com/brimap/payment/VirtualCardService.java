package com.brimap.payment;

import com.brimap.audit.AuditService;
import com.brimap.bonus.VirtualCards;
import com.brimap.common.BusinessRuleException;
import com.brimap.config.AppClock;
import com.brimap.model.Payment;
import com.brimap.model.Vendor;
import com.brimap.model.VirtualCard;
import com.brimap.repository.VendorRepository;
import com.brimap.repository.VirtualCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Brim virtual card lifecycle (D-100). Issued when a card payment is released, charged once by the vendor (or by the
 * batch run, which stands in for the vendor charging on the payment date), closed when the payment settles, cancelled
 * when it fails. Every step and every declined charge is audited against the payment.
 */
@Service
@RequiredArgsConstructor
public class VirtualCardService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VirtualCardRepository cards;
    private final VendorRepository vendors;
    private final AuditService audit;
    private final AppClock clock;

    public record ChargeResult(boolean approved, String reason, VirtualCard card) {}

    public Optional<VirtualCard> forPayment(String paymentId) { return cards.findByPaymentId(paymentId); }

    @Transactional
    public VirtualCard issue(Payment p, String issuedBy) {
        Optional<VirtualCard> existing = cards.findByPaymentId(p.getId());
        if (existing.isPresent() && !"CANCELLED".equals(existing.get().getStatus())) return existing.get();
        String number = VirtualCards.newNumber(RANDOM);
        VirtualCard c = existing.orElseGet(VirtualCard::new);
        c.setId("VC-" + p.getId().replace("PAY-", ""));
        c.setPaymentId(p.getId());
        c.setVendorId(p.getVendorId());
        c.setNetwork("Visa");
        c.setLast4(number.substring(number.length() - 4));
        c.setCurrency(p.getCurrency());
        c.setSpendLimit(p.getAmount());
        c.setExpiresOn(clock.today().plusDays(VirtualCards.VALID_DAYS));
        c.setStatus("ISSUED");
        c.setIssuedAt(clock.now());
        c.setIssuedBy(issuedBy);
        c.setChargedAmount(null);
        c.setChargedAt(null);
        c.setDeclinedAttempts(0);
        c.setLastDecline(null);
        cards.save(c);
        audit.record("VIRTUAL_CARD_ISSUED", "payment", p.getId(), null, snapshot(c),
                "Brim virtual card " + c.masked() + " issued for " + p.getId() + ": single use, locked to " + vendorName(p.getVendorId())
                        + ", limit " + c.getSpendLimit().toPlainString() + " " + c.getCurrency() + ", expires " + c.getExpiresOn());
        return c;
    }

    /** A charge against the payment's card. Declines are recorded (not thrown) so the attempt is kept in the audit log. */
    @Transactional
    public ChargeResult charge(Payment p, String merchantVendorId, BigDecimal amount) {
        VirtualCard c = cards.findByPaymentId(p.getId())
                .orElseThrow(() -> new BusinessRuleException(p.getId() + " has no virtual card; it is issued when the card payment is released"));
        String merchant = merchantVendorId == null ? null : vendorName(merchantVendorId);
        Optional<String> decline = VirtualCards.declineReason(c, merchantVendorId, merchant, amount, clock.today());
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("card", c.masked());
        attempt.put("merchant", merchant == null ? "unknown" : merchant);
        attempt.put("amount", amount == null ? "?" : amount.toPlainString());
        if (decline.isPresent()) {
            c.setDeclinedAttempts(c.getDeclinedAttempts() + 1);
            c.setLastDecline(decline.get());
            cards.save(c);
            audit.record("VIRTUAL_CARD_DECLINED", "payment", p.getId(), null, attempt, "Declined: " + decline.get());
            return new ChargeResult(false, decline.get(), c);
        }
        Map<String, Object> before = snapshot(c);
        c.setStatus("CHARGED");
        c.setChargedAmount(amount);
        c.setChargedAt(clock.now());
        cards.save(c);
        audit.record("VIRTUAL_CARD_CHARGED", "payment", p.getId(), before, snapshot(c),
                merchant + " charged " + amount.toPlainString() + " " + c.getCurrency() + " to " + c.masked() + " (single use: no further charges)");
        return new ChargeResult(true, "Approved", c);
    }

    @Transactional
    public void close(Payment p) {
        cards.findByPaymentId(p.getId()).filter(c -> "CHARGED".equals(c.getStatus())).ifPresent(c -> {
            Map<String, Object> before = snapshot(c);
            c.setStatus("CLOSED");
            cards.save(c);
            audit.record("VIRTUAL_CARD_CLOSED", "payment", p.getId(), before, snapshot(c), c.masked() + " closed: " + p.getId() + " settled");
        });
    }

    @Transactional
    public void cancel(Payment p, String reason) {
        cards.findByPaymentId(p.getId()).filter(c -> "ISSUED".equals(c.getStatus()) || "CHARGED".equals(c.getStatus())).ifPresent(c -> {
            Map<String, Object> before = snapshot(c);
            c.setStatus("CANCELLED");
            cards.save(c);
            audit.record("VIRTUAL_CARD_CANCELLED", "payment", p.getId(), before, snapshot(c), c.masked() + " cancelled: " + reason);
        });
    }

    private String vendorName(String vendorId) {
        return vendors.findById(vendorId).map(Vendor::getLegalName).orElse(vendorId);
    }

    static Map<String, Object> snapshot(VirtualCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("card", c.getId());
        m.put("number", c.masked());
        m.put("vendorId", c.getVendorId());
        m.put("limit", c.getSpendLimit() == null ? null : c.getSpendLimit().toPlainString());
        m.put("currency", c.getCurrency());
        m.put("expiresOn", String.valueOf(c.getExpiresOn()));
        m.put("status", c.getStatus());
        if (c.getChargedAmount() != null) m.put("charged", c.getChargedAmount().toPlainString());
        return m;
    }
}
