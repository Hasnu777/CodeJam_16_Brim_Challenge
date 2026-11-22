package com.brimap.payment;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.bonus.CardPaymentAdvisor;
import com.brimap.auth.CurrentUser;
import com.brimap.auth.ForbiddenException;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.config.AppClock;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.brimap.workflow.IdGenerator;
import com.brimap.workflow.InvoiceWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Payments: prepare (clerk) -> release (FM/ADM, checked by ReleaseRules) -> EFT batch (SENT) -> settle (SETTLED) or
 * fail (FAILED, rescheduled). Every payment points at the exact vendor bank-account version it pays.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final Pattern DISCOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+)\\s*net\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final PaymentRepository payments;
    private final PaymentBatchRepository batches;
    private final InvoiceRepository invoices;
    private final VendorRepository vendors;
    private final VendorBankAccountRepository accounts;
    private final BankChangeRequestRepository bankChanges;
    private final ApprovalStepRepository steps;
    private final ApprovalPolicyRepository policies;
    private final FlagRepository flags;
    private final AppUserRepository users;
    private final InvoiceWorkflowService workflow;
    private final IdGenerator ids;
    private final AuditService audit;
    private final AppClock clock;

    // ---------- prepare ----------

    /** Create SCHEDULED payments for APPROVED invoices. Approved credit notes are netted against the vendor's payment. */
    @Transactional
    public List<Payment> prepare(List<String> invoiceIds) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "prepare payments", Role.CLK);
        if (invoiceIds == null || invoiceIds.isEmpty()) throw new IllegalArgumentException("Choose at least one approved invoice");
        List<Invoice> chosen = invoiceIds.stream().map(workflow::load).toList();
        for (Invoice i : chosen) {
            if (i.getStatus() != InvoiceStatus.APPROVED) throw new BusinessRuleException(i.getId() + " is " + i.getStatus() + ", not APPROVED");
            if (i.isCreditNote()) throw new BusinessRuleException(i.getId() + " is a credit note; it is netted automatically against the vendor's next payment");
        }
        List<Payment> out = new ArrayList<>();
        for (Invoice inv : chosen) {
            Vendor v = inv.getVendorId() == null ? null : vendors.findById(inv.getVendorId()).orElse(null);
            Payment p = new Payment();
            p.setId(ids.paymentId());
            p.setInvoiceId(inv.getId());
            p.setVendorId(inv.getVendorId());
            p.setMethod(v == null || v.getPreferredPaymentMethod() == null ? "EFT" : v.getPreferredPaymentMethod());
            p.setCurrency(inv.getCurrency());
            p.setFxRateToCad(inv.getFxRateToCad());
            p.setAmount(inv.getTotal());
            p.setDiscountTaken(BigDecimal.ZERO);
            LocalDate due = inv.getDueDate() != null ? inv.getDueDate()
                    : inv.getInvoiceDate() != null && v != null && v.getPaymentTermsDays() != null ? inv.getInvoiceDate().plusDays(v.getPaymentTermsDays())
                    : clock.today();
            LocalDate scheduled = due.isBefore(clock.today()) ? clock.today() : due;
            // Early-payment discount ("2/10 net 30"): pay on day 10 and take 2% if that day hasn't passed.
            Matcher m = v == null || v.getEarlyPayDiscount() == null ? null : DISCOUNT.matcher(v.getEarlyPayDiscount());
            if (m != null && m.find() && inv.getInvoiceDate() != null) {
                LocalDate deadline = inv.getInvoiceDate().plusDays(Long.parseLong(m.group(2)));
                p.setDiscountDeadline(deadline);
                if (!deadline.isBefore(clock.today())) {
                    BigDecimal pct = new BigDecimal(m.group(1)).movePointLeft(2);
                    p.setDiscountTaken(inv.getTotal().multiply(pct).setScale(2, RoundingMode.HALF_UP));
                    p.setAmount(inv.getTotal().subtract(p.getDiscountTaken()));
                    scheduled = deadline;
                }
            }
            p.setScheduledDate(scheduled);
            // Credit notes from the same vendor that are approved and not yet used.
            List<Invoice> credits = invoices.findByVendorId(inv.getVendorId() == null ? "-" : inv.getVendorId()).stream()
                    .filter(c -> c.isCreditNote() && c.getStatus() == InvoiceStatus.APPROVED).toList();
            BigDecimal creditTotal = BigDecimal.ZERO;
            List<String> applied = new ArrayList<>();
            for (Invoice c : credits) {
                BigDecimal amt = c.getTotal().abs();
                if (creditTotal.add(amt).compareTo(p.getAmount()) > 0) continue; // never pay a negative amount
                creditTotal = creditTotal.add(amt);
                applied.add(c.getId());
                workflow.transition(c, InvoiceStatus.SCHEDULED, "CREDIT_APPLIED", "Credit " + amt.toPlainString() + " netted against " + p.getId());
            }
            if (!applied.isEmpty()) {
                p.setAmount(p.getAmount().subtract(creditTotal));
                p.setCreditApplied(creditTotal);
                p.setAppliedCreditInvoiceIds(String.join(",", applied));
            }
            p.setAmountCad(p.getFxRateToCad() == null ? p.getAmount() : p.getAmount().multiply(p.getFxRateToCad()).setScale(2, RoundingMode.HALF_UP));
            accounts.findFirstByVendorIdAndStatus(inv.getVendorId() == null ? "-" : inv.getVendorId(), "ACTIVE").ifPresent(a -> p.setBankAccountId(a.getId()));
            p.setStatus("SCHEDULED");
            p.setPreparedBy(u.id());
            payments.save(p);
            out.add(p);
            String detail = p.getId() + " " + p.getMethod() + " " + p.getCurrency() + " " + p.getAmount().toPlainString() + " on " + scheduled
                    + (p.getDiscountTaken().signum() > 0 ? " (early-pay discount " + p.getDiscountTaken().toPlainString() + ")" : "")
                    + (applied.isEmpty() ? "" : " (credit notes " + String.join(", ", applied) + " netted: " + creditTotal.toPlainString() + ")");
            workflow.transition(inv, InvoiceStatus.SCHEDULED, "PAYMENT_SCHEDULED", detail);
            audit.record("PAYMENT_PREPARED", "payment", p.getId(), null, snapshot(p), detail);
        }
        return out;
    }

    // ---------- release ----------

    public List<String> releaseBlockers(Payment p, AppUser releaser) {
        Invoice inv = p.getInvoiceId() == null ? null : invoices.findById(p.getInvoiceId()).orElse(null);
        Vendor v = p.getVendorId() == null ? null : vendors.findById(p.getVendorId()).orElse(null);
        VendorBankAccount acct = v == null ? null : accounts.findFirstByVendorIdAndStatus(v.getId(), "ACTIVE").orElse(null);
        if (acct != null && p.getBankAccountId() != null && !acct.getId().equals(p.getBankAccountId())) {
            acct = accounts.findById(p.getBankAccountId()).orElse(acct); // the version the payment was prepared against
        }
        List<Flag> blocks = inv == null ? List.of() : flags.findByInvoiceIdAndStatus(inv.getId(), "OPEN").stream()
                .filter(f -> f.getSeverity() == Severity.BLOCK).toList();
        List<BankChangeRequest> pending = v == null ? List.of() : bankChanges.findByVendorIdOrderByChangedAtDesc(v.getId()).stream()
                .filter(b -> "PENDING".equals(b.getStatus())).toList();
        List<String> out = new ArrayList<>(ReleaseRules.blockers(new ReleaseRules.Input(p, inv, releaser, inv == null ? List.of() : steps.findByInvoiceIdOrderBySeq(inv.getId()),
                v, acct, blocks, policies.findAllByOrderByPriorityAsc(), pending)));
        if (acct != null && p.getBankAccountId() != null) {
            VendorBankAccount active = accounts.findFirstByVendorIdAndStatus(v.getId(), "ACTIVE").orElse(null);
            if (active != null && !active.getId().equals(p.getBankAccountId())) {
                out.add("Vendor bank details changed since this payment was prepared (now " + active.masked() + "); re-prepare it");
            }
        }
        return out;
    }

    @Transactional
    public Payment release(String id) {
        CurrentUser u = Auth.require();
        Payment p = payments.findById(id).orElseThrow(() -> new NotFoundException("Payment " + id));
        AppUser me = users.findById(u.id()).orElseThrow();
        List<String> why = releaseBlockers(p, me);
        if (!why.isEmpty()) {
            boolean permission = why.stream().anyMatch(w -> w.contains("cannot") || w.contains("release limit") || w.contains("Only a Finance"));
            String msg = "Cannot release " + id + ": " + String.join("; ", why);
            if (permission) throw new ForbiddenException(msg);
            throw new BusinessRuleException(msg);
        }
        Map<String, Object> before = snapshot(p);
        p.setStatus("RELEASED");
        p.setReleasedBy(u.id());
        p.setReleasedAt(clock.now());
        if (p.getBankAccountId() == null && p.getVendorId() != null) {
            accounts.findFirstByVendorIdAndStatus(p.getVendorId(), "ACTIVE").ifPresent(a -> p.setBankAccountId(a.getId()));
        }
        payments.save(p);
        audit.record("PAYMENT_RELEASED", "payment", id, before, snapshot(p), "Released by " + me.getFullName() + " (" + me.getRole() + ")");
        Invoice inv = workflow.load(p.getInvoiceId());
        if (inv.getStatus() == InvoiceStatus.SCHEDULED) workflow.transition(inv, InvoiceStatus.RELEASED, "PAYMENT_RELEASED", id + " released by " + u.id());
        return p;
    }

    // ---------- payment method (Brim card optimizer) ----------

    /** Switch a SCHEDULED payment between EFT and the Brim AP card. Card only for vendors that accept it. */
    @Transactional
    public Payment changeMethod(String id, String method) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "change payment methods", Role.CLK, Role.FM, Role.ADM);
        Payment p = payments.findById(id).orElseThrow(() -> new NotFoundException("Payment " + id));
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!m.equals("CARD") && !m.equals("EFT")) throw new IllegalArgumentException("Method must be CARD or EFT");
        if (!"SCHEDULED".equals(p.getStatus())) throw new BusinessRuleException(id + " is " + p.getStatus() + "; only SCHEDULED payments can change method");
        if (m.equals(p.getMethod())) return p;
        Vendor v = p.getVendorId() == null ? null : vendors.findById(p.getVendorId()).orElse(null);
        if (m.equals("CARD") && (v == null || !v.isAcceptsCardPayment())) throw new BusinessRuleException((v == null ? "Unknown vendor" : v.getLegalName()) + " does not accept card payments");
        Map<String, Object> before = snapshot(p);
        before.put("method", p.getMethod());
        p.setMethod(m);
        payments.save(p);
        Map<String, Object> after = snapshot(p);
        after.put("method", m);
        CardPaymentAdvisor.Advice a = advice(p, v);
        audit.record("PAYMENT_METHOD_CHANGED", "payment", id, before, after,
                before.get("method") + " -> " + m + (m.equals("CARD") && a.benefits() ? " (" + a.headline() + ")" : ""));
        return p;
    }

    public CardPaymentAdvisor.Advice advice(Payment p, Vendor v) {
        return CardPaymentAdvisor.advise(new CardPaymentAdvisor.Input(v != null && v.isAcceptsCardPayment(), p.getMethod(), p.getCurrency(),
                p.getAmountCad(), p.getScheduledDate(), p.getDiscountTaken()), CardPaymentAdvisor.Terms.DEFAULT);
    }

    // ---------- EFT batch, settle, fail ----------

    @Transactional
    public PaymentBatch batch() {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "create payment batches", Role.FM, Role.ADM);
        List<Payment> released = payments.findByStatus("RELEASED");
        if (released.isEmpty()) throw new BusinessRuleException("No released payments to batch");
        PaymentBatch b = new PaymentBatch();
        b.setId(ids.batchId());
        b.setCreatedAt(clock.now());
        b.setCreatedBy(u.id());
        StringBuilder file = new StringBuilder("# Simulated EFT file " + b.getId() + " - Harborline Logistics - " + clock.today() + "\n");
        file.append("payment_id,payee,institution,transit,account,currency,amount,reference\n");
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : released) {
            Vendor v = p.getVendorId() == null ? null : vendors.findById(p.getVendorId()).orElse(null);
            VendorBankAccount a = p.getBankAccountId() == null ? null : accounts.findById(p.getBankAccountId()).orElse(null);
            boolean card = "CARD".equals(p.getMethod());
            p.setStatus("SENT");
            p.setBatchId(b.getId());
            p.setReference(p.getReference() != null ? p.getReference() : "BRIM-" + p.getMethod() + "-" + p.getId().replace("PAY-", ""));
            payments.save(p);
            file.append(p.getId()).append(',').append(v == null ? "" : v.getLegalName().replace(",", " ")).append(',')
                    .append(card || a == null ? "" : a.getInstitution()).append(',').append(card || a == null ? "" : a.getTransit()).append(',')
                    .append(card ? "BRIM-AP-CARD" : a == null ? "" : a.getAccountNumber()).append(',').append(p.getCurrency()).append(',').append(p.getAmount().toPlainString())
                    .append(',').append(p.getReference()).append('\n');
            total = total.add(p.getAmountCad() == null ? BigDecimal.ZERO : p.getAmountCad());
            audit.record("PAYMENT_SENT", "payment", p.getId(), Map.of("status", "RELEASED"), Map.of("status", "SENT", "batch", b.getId()), "In EFT batch " + b.getId());
        }
        b.setPaymentCount(released.size());
        b.setTotalCad(total);
        b.setFileContent(file.toString());
        batches.save(b);
        audit.record("EFT_BATCH_CREATED", "payment_batch", b.getId(), null, Map.of("payments", released.size(), "totalCad", total.toPlainString()), null);
        return b;
    }

    @Transactional
    public Payment settle(String id, LocalDate paidDate) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "settle payments", Role.FM, Role.ADM);
        Payment p = payments.findById(id).orElseThrow(() -> new NotFoundException("Payment " + id));
        if (!"SENT".equals(p.getStatus())) throw new BusinessRuleException(id + " is " + p.getStatus() + "; only SENT payments can be settled");
        p.setStatus("SETTLED");
        p.setPaidDate(paidDate != null ? paidDate : clock.today());
        payments.save(p);
        audit.record("PAYMENT_SETTLED", "payment", id, Map.of("status", "SENT"), Map.of("status", "SETTLED", "paidDate", p.getPaidDate().toString()), null);
        Invoice inv = workflow.load(p.getInvoiceId());
        if (inv.getStatus() == InvoiceStatus.RELEASED) workflow.transition(inv, InvoiceStatus.PAID, "PAID", id + " settled " + p.getPaidDate());
        if (p.getAppliedCreditInvoiceIds() != null) {
            for (String cid : p.getAppliedCreditInvoiceIds().split(",")) {
                Invoice c = workflow.load(cid);
                if (c.getStatus() == InvoiceStatus.SCHEDULED) c = workflow.transition(c, InvoiceStatus.RELEASED, "CREDIT_RELEASED", "Netted in " + id);
                if (c.getStatus() == InvoiceStatus.RELEASED) workflow.transition(c, InvoiceStatus.PAID, "CREDIT_USED", "Netted in " + id);
            }
        }
        return p;
    }

    @Transactional
    public Payment fail(String id, String reason) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "mark payments failed", Role.FM, Role.ADM);
        Payment p = payments.findById(id).orElseThrow(() -> new NotFoundException("Payment " + id));
        if (!"SENT".equals(p.getStatus()) && !"RELEASED".equals(p.getStatus())) throw new BusinessRuleException(id + " is " + p.getStatus());
        p.setStatus("FAILED");
        p.setFailureReason(reason);
        payments.save(p);
        audit.record("PAYMENT_FAILED", "payment", id, null, Map.of("status", "FAILED"), reason);
        Invoice inv = workflow.load(p.getInvoiceId());
        if (inv.getStatus() == InvoiceStatus.RELEASED) {
            workflow.transition(inv, InvoiceStatus.SCHEDULED, "PAYMENT_FAILED", id + " failed: " + reason);
            Payment retry = new Payment();
            retry.setId(ids.paymentId());
            retry.setInvoiceId(p.getInvoiceId()); retry.setVendorId(p.getVendorId()); retry.setMethod(p.getMethod());
            retry.setCurrency(p.getCurrency()); retry.setAmount(p.getAmount()); retry.setFxRateToCad(p.getFxRateToCad()); retry.setAmountCad(p.getAmountCad());
            retry.setDiscountTaken(p.getDiscountTaken()); retry.setScheduledDate(clock.today()); retry.setStatus("SCHEDULED");
            retry.setPreparedBy(u.id()); retry.setBankAccountId(p.getBankAccountId());
            payments.save(retry);
            audit.record("PAYMENT_PREPARED", "payment", retry.getId(), null, snapshot(retry), "Retry of failed " + id);
        }
        return p;
    }

    // ---------- read ----------

    public record PaymentRow(Payment payment, String vendorName, String invoiceNumber, String bankAccount, boolean bankVerified,
                             List<String> releaseBlockers, boolean earlyDiscountAvailable, CardPaymentAdvisor.Advice cardAdvice) {}

    @Transactional(readOnly = true)
    public List<PaymentRow> list(String status, boolean includeHistory) {
        CurrentUser u = Auth.require();
        AppUser me = users.findById(u.id()).orElseThrow();
        Map<String, Vendor> vendorById = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, v -> v));
        Map<String, String> vendorNames = vendorById.values().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        Map<String, Invoice> invs = invoices.findAll().stream().collect(Collectors.toMap(Invoice::getId, i -> i));
        List<Payment> rows = status == null || status.isBlank() ? payments.findAll()
                : Arrays.stream(status.split(",")).flatMap(s -> payments.findByStatus(s.trim().toUpperCase()).stream()).toList();
        List<PaymentRow> out = new ArrayList<>();
        for (Payment p : rows) {
            if (!includeHistory && p.isHistorical() && !"SCHEDULED".equals(p.getStatus())) continue;
            VendorBankAccount a = p.getBankAccountId() == null ? null : accounts.findById(p.getBankAccountId()).orElse(null);
            if (a == null && p.getVendorId() != null) a = accounts.findFirstByVendorIdAndStatus(p.getVendorId(), "ACTIVE").orElse(null);
            List<String> blockers = "SCHEDULED".equals(p.getStatus()) && (me.getRole() == Role.FM || me.getRole() == Role.ADM || me.getRole() == Role.CLK)
                    ? releaseBlockers(p, me) : List.of();
            Invoice inv = invs.get(p.getInvoiceId());
            out.add(new PaymentRow(p, vendorNames.getOrDefault(p.getVendorId(), p.getVendorId()), inv == null ? null : inv.getVendorInvoiceNumber(),
                    a == null ? null : a.masked(), a != null && a.isVerified(), blockers,
                    p.getDiscountDeadline() != null && !p.getDiscountDeadline().isBefore(clock.today()) && "SCHEDULED".equals(p.getStatus()),
                    "SCHEDULED".equals(p.getStatus()) ? advice(p, vendorById.get(p.getVendorId())) : null));
        }
        out.sort(Comparator.comparing((PaymentRow r) -> r.payment().getScheduledDate() == null ? LocalDate.MAX : r.payment().getScheduledDate()));
        return out;
    }

    public List<PaymentBatch> batches() { return batches.findAll(); }

    static Map<String, Object> snapshot(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", p.getStatus());
        m.put("invoiceId", p.getInvoiceId());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("amountCad", p.getAmountCad());
        m.put("scheduledDate", p.getScheduledDate());
        m.put("bankAccountId", p.getBankAccountId());
        m.put("releasedBy", p.getReleasedBy());
        return m;
    }
}
