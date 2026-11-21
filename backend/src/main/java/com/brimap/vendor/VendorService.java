package com.brimap.vendor;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.auth.ForbiddenException;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.config.AppClock;
import com.brimap.extraction.VendorMatcher;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.brimap.validation.RevalidationService;
import com.brimap.workflow.IdGenerator;
import com.brimap.workflow.InvoiceWorkflowService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vendor master: onboarding (pending_review until a Finance Manager other than the creator approves), and bank
 * detail changes as requests that create a new, unverified account version and hold payments until a second
 * person verifies them by callback. Bank details are never edited in place.
 */
@Service
@RequiredArgsConstructor
public class VendorService {
    private final VendorRepository vendors;
    private final VendorBankAccountRepository accounts;
    private final BankChangeRequestRepository changes;
    private final ContractRepository contracts;
    private final InvoiceRepository invoices;
    private final CardTransactionRepository cards;
    private final FlagRepository flags;
    private final PaymentRepository payments;
    private final IdGenerator ids;
    private final AuditService audit;
    private final AppClock clock;
    private final RevalidationService revalidation;
    private final InvoiceWorkflowService workflow;

    public record VendorRow(Vendor vendor, BigDecimal spendYtdCad, long invoiceCount, long openFlags, String bankAccount, boolean bankVerified,
                            boolean pendingBankChange) {}

    @Transactional(readOnly = true)
    public List<VendorRow> list() {
        int year = clock.today().getYear();
        Map<String, List<Invoice>> byVendor = invoices.findAll().stream().filter(i -> i.getVendorId() != null).collect(Collectors.groupingBy(Invoice::getVendorId));
        Map<String, Long> openFlags = new HashMap<>();
        Map<String, String> invVendor = invoices.findAll().stream().filter(i -> i.getVendorId() != null).collect(Collectors.toMap(Invoice::getId, Invoice::getVendorId));
        flags.findAll().stream().filter(Flag::isOpen).forEach(f -> {
            String v = invVendor.get(f.getInvoiceId());
            if (v != null) openFlags.merge(v, 1L, Long::sum);
        });
        Set<String> pending = changes.findByStatus("PENDING").stream().map(BankChangeRequest::getVendorId).collect(Collectors.toSet());
        List<VendorRow> out = new ArrayList<>();
        for (Vendor v : vendors.findAll()) {
            List<Invoice> inv = byVendor.getOrDefault(v.getId(), List.of());
            BigDecimal ytd = inv.stream().filter(i -> i.getInvoiceDate() != null && i.getInvoiceDate().getYear() == year && i.getTotalCad() != null
                            && (i.getStatus() == InvoiceStatus.PAID || i.getStatus() == InvoiceStatus.SCHEDULED || i.getStatus() == InvoiceStatus.APPROVED
                            || i.getStatus() == InvoiceStatus.RELEASED))
                    .map(Invoice::getTotalCad).reduce(BigDecimal.ZERO, BigDecimal::add);
            VendorBankAccount a = accounts.findFirstByVendorIdAndStatus(v.getId(), "ACTIVE").orElse(null);
            out.add(new VendorRow(v, ytd, inv.size(), openFlags.getOrDefault(v.getId(), 0L), a == null ? null : a.masked(), a != null && a.isVerified(),
                    pending.contains(v.getId())));
        }
        out.sort(Comparator.comparing(r -> r.vendor().getId()));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String id) {
        Vendor v = vendors.findById(id).orElseThrow(() -> new NotFoundException("Vendor " + id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendor", v);
        m.put("bankAccounts", accounts.findByVendorIdOrderByVersionDesc(id));
        m.put("bankChangeRequests", changes.findByVendorIdOrderByChangedAtDesc(id));
        m.put("contracts", contracts.findByVendorId(id));
        List<Invoice> inv = invoices.findByVendorId(id).stream().sorted(Comparator.comparing(Invoice::getInvoiceDate,
                Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        m.put("invoices", inv);
        m.put("payments", payments.findAll().stream().filter(p -> id.equals(p.getVendorId())).toList());
        m.put("cardTransactions", cards.findAll().stream().filter(t -> com.brimap.validation.rules.D04PaidByCard
                .merchantMatches(t.getMerchantDescriptor(), v.getLegalName())).toList());
        m.put("lookalikes", vendors.findAll().stream().filter(o -> !o.getId().equals(id) && VendorMatcher.similarity(o.getLegalName(), v.getLegalName()) >= 0.85)
                .map(o -> Map.of("id", o.getId(), "legalName", o.getLegalName(), "similarity", Math.round(VendorMatcher.similarity(o.getLegalName(), v.getLegalName()) * 100) / 100.0)).toList());
        return m;
    }

    public record OnboardRequest(@NotBlank String legalName, String category, String defaultGlAccount, String address, String provinceOrState,
                                 String country, String gstHstNumber, String qstNumber, Integer paymentTermsDays, String preferredPaymentMethod,
                                 String apContactEmail, Boolean requiresPo, String bankInstitution, String bankTransit, String bankAccount,
                                 String fromInvoiceId) {}

    /** Onboard -> pending_review. Optionally attach it to the unknown-vendor bill it came from. */
    @Transactional
    public Vendor onboard(OnboardRequest r) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "onboard vendors", Role.CLK, Role.ADM);
        if (r.legalName() == null || r.legalName().isBlank()) throw new IllegalArgumentException("Legal name is required");
        String gst = r.gstHstNumber() == null || r.gstHstNumber().isBlank() ? null : VendorMatcher.canonicalGst(r.gstHstNumber());
        if (r.gstHstNumber() != null && !r.gstHstNumber().isBlank() && gst == null) throw new IllegalArgumentException("GST number must look like 123456789RT0001");
        if (gst != null) vendors.findAll().stream().filter(v -> gst.equals(v.getGstHstNumber())).findFirst().ifPresent(v -> {
            throw new BusinessRuleException("GST number " + gst + " already belongs to " + v.getId() + " " + v.getLegalName());
        });
        Vendor v = new Vendor();
        v.setId(ids.vendorId());
        v.setLegalName(r.legalName().trim());
        v.setCategory(r.category());
        v.setDefaultGlAccount(r.defaultGlAccount());
        v.setAddress(r.address());
        v.setProvinceOrState(r.provinceOrState());
        v.setCountry(r.country() == null ? "CA" : r.country());
        v.setGstHstNumber(gst);
        v.setQstNumber(r.qstNumber());
        v.setPaymentTermsDays(r.paymentTermsDays() == null ? 30 : r.paymentTermsDays());
        v.setPreferredPaymentMethod(r.preferredPaymentMethod() == null ? "EFT" : r.preferredPaymentMethod());
        v.setApContactEmail(r.apContactEmail());
        v.setRequiresPo(Boolean.TRUE.equals(r.requiresPo()));
        v.setStatus("pending_review");
        v.setCreatedBy(u.id());
        vendors.save(v);
        if (r.bankAccount() != null && !r.bankAccount().isBlank()) {
            VendorBankAccount a = new VendorBankAccount();
            a.setVendorId(v.getId()); a.setVersion(1); a.setInstitution(r.bankInstitution()); a.setTransit(r.bankTransit());
            a.setAccountNumber(r.bankAccount().replaceAll("\\D", "")); a.setLast4(last4(a.getAccountNumber()));
            a.setStatus("ACTIVE"); a.setVerified(false); a.setCreatedAt(Instant.now());
            accounts.save(a); // verified when the vendor is approved by a second person
        }
        audit.record("VENDOR_ONBOARDED", "vendor", v.getId(), null, Map.of("legalName", v.getLegalName(), "status", v.getStatus(),
                "gst", String.valueOf(gst)), "Pending review until a Finance Manager approves");
        if (r.fromInvoiceId() != null) {
            Invoice inv = workflow.load(r.fromInvoiceId());
            if (inv.getVendorId() == null) {
                Map<String, Object> before = Map.of("vendorId", "null");
                inv.setVendorId(v.getId());
                inv.setVendorMatchMethod("MANUAL");
                workflow.recordChange(inv, "VENDOR_LINKED", before, Map.of("vendorId", v.getId()), "Linked to newly onboarded " + v.getId());
                revalidation.revalidateVendor(probe(v.getId()));
            }
        }
        return v;
    }

    /** FM/ADM, not the person who onboarded it: activates the vendor and verifies its initial bank account. */
    @Transactional
    public Vendor approve(String id, String note) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "approve vendors", Role.FM, Role.ADM);
        Vendor v = vendors.findById(id).orElseThrow(() -> new NotFoundException("Vendor " + id));
        if ("active".equals(v.getStatus())) throw new BusinessRuleException(v.getLegalName() + " is already active");
        if (u.id().equals(v.getCreatedBy())) throw new ForbiddenException("You onboarded " + v.getLegalName() + "; a different person must approve it");
        String before = v.getStatus();
        v.setStatus("active");
        v.setApprovedBy(u.id());
        v.setOnboardedAt(clock.today()); // "new vendor" (P05) counts from approval
        vendors.save(v);
        accounts.findByVendorIdOrderByVersionDesc(id).stream().filter(a -> "ACTIVE".equals(a.getStatus()) && !a.isVerified()
                        && changes.findByVendorIdOrderByChangedAtDesc(id).stream().noneMatch(c -> "PENDING".equals(c.getStatus())))
                .forEach(a -> { a.setVerified(true); a.setVerifiedBy(u.id()); accounts.save(a); });
        audit.record("VENDOR_APPROVED", "vendor", id, Map.of("status", before), Map.of("status", "active"), note);
        revalidation.revalidateVendor(probe(id));
        return v;
    }

    public record BankChangeRequestBody(String institution, String transit, @NotBlank String accountNumber, String channel, String note) {}

    /** Clerk/Admin records a request; it never touches the active account. Payments to the vendor are held (B02). */
    @Transactional
    public BankChangeRequest requestChange(String vendorId, BankChangeRequestBody r) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "request bank changes", Role.CLK, Role.ADM);
        Vendor v = vendors.findById(vendorId).orElseThrow(() -> new NotFoundException("Vendor " + vendorId));
        if (r.accountNumber() == null || r.accountNumber().replaceAll("\\D", "").length() < 5) throw new IllegalArgumentException("Account number is required");
        if (changes.findByVendorIdOrderByChangedAtDesc(vendorId).stream().anyMatch(c -> "PENDING".equals(c.getStatus()))) {
            throw new BusinessRuleException(v.getLegalName() + " already has a bank change waiting for verification");
        }
        VendorBankAccount current = accounts.findFirstByVendorIdAndStatus(vendorId, "ACTIVE").orElse(null);
        String acct = r.accountNumber().replaceAll("\\D", "");
        BankChangeRequest c = new BankChangeRequest();
        c.setId(ids.bankChangeId());
        c.setVendorId(vendorId);
        c.setChangedAt(clock.today());
        c.setRequestedBy(u.id());
        c.setRequestChannel(r.channel() == null ? "email_request" : r.channel());
        c.setVerifiedByCallback(false);
        c.setOldAccountLast4(current == null ? null : current.getLast4());
        c.setNewAccountLast4(last4(acct));
        c.setNewInstitution(r.institution());
        c.setNewTransit(r.transit());
        c.setNewAccountNumber(acct);
        c.setStatus("PENDING");
        c.setNote(r.note());
        changes.save(c);
        VendorBankAccount a = new VendorBankAccount();
        a.setVendorId(vendorId);
        a.setVersion(accounts.findByVendorIdOrderByVersionDesc(vendorId).stream().mapToInt(VendorBankAccount::getVersion).max().orElse(0) + 1);
        a.setInstitution(r.institution()); a.setTransit(r.transit()); a.setAccountNumber(acct); a.setLast4(last4(acct));
        a.setStatus("PENDING"); a.setVerified(false); a.setCreatedAt(Instant.now()); a.setBankChangeRequestId(c.getId());
        accounts.save(a);
        audit.record("BANK_CHANGE_REQUESTED", "vendor", vendorId, Map.of("account", current == null ? "none" : current.masked()),
                Map.of("requestedAccount", a.masked(), "request", c.getId(), "channel", c.getRequestChannel()), r.note());
        revalidation.revalidateVendor(probe(vendorId));
        return c;
    }

    public record VerifyBody(boolean callbackDone, Boolean approve, String note) {}

    /**
     * A second person (FM/ADM, not the requester) verifies by calling the vendor on a number already on file.
     * Approve: the new version becomes ACTIVE + verified, the old one SUPERSEDED. Reject: the requested version is
     * REJECTED; if the dataset had already applied it (BC-009), the vendor goes on hold until real details are confirmed.
     */
    @Transactional
    public BankChangeRequest verify(String changeId, VerifyBody b) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "verify bank changes", Role.FM, Role.ADM);
        BankChangeRequest c = changes.findById(changeId).orElseThrow(() -> new NotFoundException("Bank change " + changeId));
        if (!"PENDING".equals(c.getStatus())) throw new BusinessRuleException(changeId + " is already " + c.getStatus());
        if (u.id().equals(c.getRequestedBy())) throw new ForbiddenException("You requested " + changeId + "; a different person must verify it");
        boolean approve = b.approve() == null || b.approve();
        if (approve && !b.callbackDone()) throw new BusinessRuleException("Call the vendor back on a number already on file before approving the change");
        Vendor v = vendors.findById(c.getVendorId()).orElseThrow();
        List<VendorBankAccount> versions = accounts.findByVendorIdOrderByVersionDesc(v.getId());
        VendorBankAccount requested = versions.stream().filter(a -> changeId.equals(a.getBankChangeRequestId())).findFirst().orElse(null);
        if (approve) {
            for (VendorBankAccount a : versions) {
                if (a != requested && "ACTIVE".equals(a.getStatus())) { a.setStatus("SUPERSEDED"); accounts.save(a); }
            }
            if (requested != null) {
                requested.setStatus("ACTIVE"); requested.setVerified(true); requested.setVerifiedBy(u.id());
                accounts.save(requested);
            }
            c.setStatus("VERIFIED");
            c.setVerifiedByCallback(true);
            v.setBankDetailsUpdatedAt(clock.today());
        } else {
            if (requested != null) {
                boolean wasApplied = "ACTIVE".equals(requested.getStatus());
                requested.setStatus("REJECTED"); requested.setVerified(false); accounts.save(requested);
                if (wasApplied) {
                    v.setStatus("on_hold"); // the master had already been switched; no trusted account remains
                }
            }
            c.setStatus("REJECTED");
        }
        c.setVerifiedBy(u.id());
        c.setVerifiedAt(clock.now());
        c.setNote((c.getNote() == null ? "" : c.getNote() + " | ") + (b.note() == null ? "" : b.note()));
        changes.save(c);
        vendors.save(v);
        audit.record(approve ? "BANK_CHANGE_VERIFIED" : "BANK_CHANGE_REJECTED", "vendor", v.getId(), Map.of("request", changeId, "status", "PENDING"),
                Map.of("request", changeId, "status", c.getStatus(), "callbackDone", b.callbackDone()), b.note());
        revalidation.revalidateVendor(probe(v.getId()));
        return c;
    }

    private static Invoice probe(String vendorId) {
        Invoice i = new Invoice();
        i.setVendorId(vendorId);
        i.setId("-");
        return i;
    }

    private static String last4(String acct) { return acct.length() > 4 ? acct.substring(acct.length() - 4) : acct; }

    public static LocalDate toDate(Instant i) { return i == null ? null : i.atZone(ZoneOffset.UTC).toLocalDate(); }
}
