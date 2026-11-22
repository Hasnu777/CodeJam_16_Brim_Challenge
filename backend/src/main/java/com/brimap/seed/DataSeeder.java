package com.brimap.seed;

import com.brimap.config.AppProperties;
import com.brimap.duplicates.InvoiceNumbers;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static com.brimap.seed.Csv.*;

/**
 * Loads every file in the dataset folder into the database. Idempotent: {@link #reset()} truncates every
 * table first. Row counts are compared against the dataset README in {@link #EXPECTED}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataSeeder {

    /** Row counts stated in dataset_given/README.md. */
    public static final Map<String, Integer> EXPECTED = Map.ofEntries(
            Map.entry("approval_policies", 13), Map.entry("approvals", 474), Map.entry("bank_change_history", 9),
            Map.entry("bank_statement", 354), Map.entry("card_transactions", 590), Map.entry("contracts", 27),
            Map.entry("departments", 8), Map.entry("fx_rates", 306), Map.entry("gl_accounts", 30),
            Map.entry("invoice_lines", 809), Map.entry("invoices", 426), Map.entry("payments", 415),
            Map.entry("purchase_orders", 52), Map.entry("tax_codes", 6), Map.entry("users", 40),
            Map.entry("vendors", 56), Map.entry("emails", 59), Map.entry("incoming_invoice_files", 56));

    private final AppProperties props;
    private final EntityManager em;
    private final ObjectMapper json;
    private final DepartmentRepository departments;
    private final AppUserRepository users;
    private final DelegationRepository delegations;
    private final GlAccountRepository glAccounts;
    private final TaxCodeRepository taxCodes;
    private final FxRateRepository fxRates;
    private final ContractRepository contracts;
    private final PurchaseOrderRepository purchaseOrders;
    private final VendorRepository vendors;
    private final VendorBankAccountRepository bankAccounts;
    private final BankChangeRequestRepository bankChanges;
    private final EmailMessageRepository emails;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository invoiceLines;
    private final ApprovalPolicyRepository policies;
    private final ApprovalStepRepository approvalSteps;
    private final PaymentRepository payments;
    private final BankStatementLineRepository statementLines;
    private final CardTransactionRepository cardTxns;
    private final FlagDictionarySeed flagDictionarySeed;

    public Path dataDir() { return Path.of(props.dataDir()); }

    private Path csv(String name) { return dataDir().resolve("data").resolve(name + ".csv"); }

    public boolean isEmpty() { return users.count() == 0; }

    @Transactional
    public SeedReport reset() {
        @SuppressWarnings("unchecked")
        List<String> tables = em.createNativeQuery("select tablename from pg_tables where schemaname = 'public'").getResultList();
        // Lets the audit_events TRUNCATE guard (after-ddl.sql) through, for this transaction only.
        em.createNativeQuery("select set_config('app.allow_reset', 'on', true)").getSingleResult();
        if (!tables.isEmpty()) {
            em.createNativeQuery("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE").executeUpdate();
        }
        for (String[] seq : new String[][]{{"invoice_number_seq", "427"}, {"payment_number_seq", "416"},
                {"vendor_number_seq", "57"}, {"bank_change_number_seq", "10"}, {"batch_number_seq", "1"}}) {
            em.createNativeQuery("ALTER SEQUENCE " + seq[0] + " RESTART WITH " + seq[1]).executeUpdate();
        }
        em.flush();
        em.clear();
        return seed();
    }

    @Transactional
    public SeedReport seed() {
        long t0 = System.currentTimeMillis();
        Map<String, Integer> loaded = new LinkedHashMap<>();

        // Reference data
        loaded.put("departments", saveAll(departments, read("departments"), r -> {
            Department d = new Department();
            d.setId(r.get("department_id")); d.setName(r.get("name")); d.setCostCentre(r.get("cost_centre"));
            d.setAnnualBudgetCad(money(r.get("annual_budget_cad"))); d.setManagerUserId(r.get("manager_user_id"));
            return d;
        }));
        List<Map<String, String>> userRows = read("users");
        loaded.put("users", saveAll(users, userRows, r -> {
            AppUser u = new AppUser();
            u.setId(r.get("user_id")); u.setFullName(r.get("full_name")); u.setEmail(r.get("email")); u.setTitle(r.get("title"));
            u.setDepartmentId(r.get("department_id")); u.setManagerId(r.get("manager_id")); u.setRole(Role.fromDataset(r.get("role")));
            u.setApprovalLimitCad(money(r.get("approval_limit_cad"))); u.setWorkProvince(r.get("work_province"));
            u.setStatus(UserStatus.valueOf(r.get("status"))); u.setStatusSince(date(r.get("status_since")));
            u.setDelegateUserId(r.get("delegate_user_id")); u.setHireDate(date(r.get("hire_date")));
            return u;
        }));
        // Time-bounded delegations derived from on_leave users (open-ended until they return).
        for (Map<String, String> r : userRows) {
            if ("on_leave".equals(r.get("status")) && r.get("delegate_user_id") != null) {
                Delegation d = new Delegation();
                d.setFromUserId(r.get("user_id")); d.setToUserId(r.get("delegate_user_id"));
                d.setStartDate(date(r.get("status_since"))); d.setReason("On leave (HR record)");
                delegations.save(d);
            }
        }
        loaded.put("gl_accounts", saveAll(glAccounts, read("gl_accounts"), r -> {
            GlAccount g = new GlAccount();
            g.setAccountNumber(r.get("account_number")); g.setName(r.get("name")); g.setAccountType(r.get("account_type")); g.setNotes(r.get("notes"));
            return g;
        }));
        loaded.put("tax_codes", saveAll(taxCodes, read("tax_codes"), r -> {
            TaxCode t = new TaxCode();
            t.setCode(r.get("code")); t.setDescription(r.get("description")); t.setRate(dec(r.get("rate")));
            t.setAppliesTo(r.get("applies_to")); t.setRecoverable(r.get("recoverable")); t.setGlAccount(r.get("gl_account"));
            return t;
        }));
        loaded.put("fx_rates", saveAll(fxRates, read("fx_rates"), r -> {
            FxRate f = new FxRate();
            f.setDate(date(r.get("date"))); f.setUsdToCad(dec(r.get("usd_to_cad")));
            return f;
        }));
        loaded.put("contracts", saveAll(contracts, read("contracts"), r -> {
            Contract c = new Contract();
            c.setId(r.get("contract_id")); c.setVendorId(r.get("vendor_id")); c.setDescription(r.get("description"));
            c.setBillingType(r.get("billing_type")); c.setMonthlyFee(money(r.get("monthly_fee"))); c.setCurrency(r.get("currency"));
            c.setHourlyRateCap(money(r.get("hourly_rate_cap"))); c.setFuelSurchargeCapPct(dec(r.get("fuel_surcharge_cap_pct")));
            c.setNotToExceedMonthly(money(r.get("not_to_exceed_monthly"))); c.setStartDate(date(r.get("start_date")));
            c.setEndDate(date(r.get("end_date"))); c.setAutoRenew(yes(r.get("auto_renew"))); c.setStatus(r.get("status"));
            return c;
        }));
        loaded.put("purchase_orders", saveAll(purchaseOrders, read("purchase_orders"), r -> {
            PurchaseOrder p = new PurchaseOrder();
            p.setId(r.get("po_id")); p.setVendorId(r.get("vendor_id")); p.setDepartmentId(r.get("department_id"));
            p.setRequesterUserId(r.get("requester_user_id")); p.setIssueDate(date(r.get("issue_date"))); p.setDescription(r.get("description"));
            p.setTotalAmount(money(r.get("total_amount"))); p.setCurrency(r.get("currency")); p.setStatus(r.get("status")); p.setApprovedBy(r.get("approved_by"));
            return p;
        }));

        // Vendors + versioned bank accounts + change history
        List<Map<String, String>> vendorRows = read("vendors");
        loaded.put("vendors", saveAll(vendors, vendorRows, r -> {
            Vendor v = new Vendor();
            v.setId(r.get("vendor_id")); v.setLegalName(r.get("legal_name")); v.setCategory(r.get("category"));
            v.setDefaultGlAccount(r.get("default_gl_account")); v.setAddress(r.get("address")); v.setProvinceOrState(r.get("province_or_state"));
            v.setCountry(r.get("country")); v.setGstHstNumber(r.get("gst_hst_number")); v.setQstNumber(r.get("qst_number"));
            v.setPaymentTermsDays(integer(r.get("payment_terms_days"))); v.setEarlyPayDiscount(r.get("early_pay_discount"));
            v.setPreferredPaymentMethod(r.get("preferred_payment_method")); v.setAcceptsCardPayment(yes(r.get("accepts_card_payment")));
            v.setBankDetailsUpdatedAt(date(r.get("bank_details_updated_at"))); v.setApContactEmail(r.get("ap_contact_email"));
            v.setRequiresPo(yes(r.get("requires_po"))); v.setStatus(r.get("status")); v.setOnboardedAt(date(r.get("onboarded_at")));
            return v;
        }));
        List<Map<String, String>> changeRows = read("bank_change_history");
        Map<String, Map<String, String>> latestChangeByVendor = new HashMap<>();
        loaded.put("bank_change_history", saveAll(bankChanges, changeRows, r -> {
            BankChangeRequest b = new BankChangeRequest();
            b.setId(r.get("change_id")); b.setVendorId(r.get("vendor_id")); b.setChangedAt(date(r.get("changed_at")));
            b.setRequestedBy(r.get("changed_by_user_id")); b.setRequestChannel(r.get("request_channel"));
            b.setVerifiedByCallback(yes(r.get("verified_by_callback"))); b.setOldAccountLast4(r.get("old_account_last4"));
            b.setNewAccountLast4(r.get("new_account_last4"));
            b.setStatus(b.isVerifiedByCallback() ? "VERIFIED" : "PENDING");
            // The dataset already wrote unverified changes into the vendor master; we model that as "applied before verification".
            b.setAppliedBeforeVerification(!b.isVerifiedByCallback());
            latestChangeByVendor.merge(r.get("vendor_id"), r, (a, c) -> date(c.get("changed_at")).isAfter(date(a.get("changed_at"))) ? c : a);
            return b;
        }));
        for (Map<String, String> r : vendorRows) {
            String acct = r.get("bank_account");
            if (acct == null) continue; // card-only vendors
            Map<String, String> change = latestChangeByVendor.get(r.get("vendor_id"));
            int version = 1;
            if (change != null) {
                VendorBankAccount old = new VendorBankAccount();
                old.setVendorId(r.get("vendor_id")); old.setVersion(1); old.setLast4(change.get("old_account_last4"));
                old.setStatus("SUPERSEDED"); old.setVerified(true);
                old.setCreatedAt(date(r.get("onboarded_at")).atStartOfDay().toInstant(ZoneOffset.UTC));
                bankAccounts.save(old);
                version = 2;
            }
            VendorBankAccount cur = new VendorBankAccount();
            cur.setVendorId(r.get("vendor_id")); cur.setVersion(version);
            cur.setInstitution(r.get("bank_institution")); cur.setTransit(r.get("bank_transit")); cur.setAccountNumber(acct);
            cur.setLast4(acct.length() >= 4 ? acct.substring(acct.length() - 4) : acct);
            cur.setStatus("ACTIVE");
            cur.setVerified(change == null || yes(change.get("verified_by_callback")));
            cur.setBankChangeRequestId(change == null ? null : change.get("change_id"));
            String since = change != null ? change.get("changed_at") : r.get("onboarded_at");
            cur.setCreatedAt(date(since).atStartOfDay().toInstant(ZoneOffset.UTC));
            bankAccounts.save(cur);
            if (change != null) {
                bankChanges.findById(change.get("change_id")).ifPresent(b -> {
                    b.setNewInstitution(cur.getInstitution()); b.setNewTransit(cur.getTransit()); b.setNewAccountNumber(acct);
                });
            }
        }

        // History
        loaded.put("invoices", saveAll(invoices, read("invoices"), r -> {
            Invoice i = new Invoice();
            i.setId(r.get("invoice_id")); i.setVendorId(r.get("vendor_id")); i.setVendorInvoiceNumber(r.get("vendor_invoice_number"));
            i.setNormalizedInvoiceNumber(InvoiceNumbers.normalize(r.get("vendor_invoice_number")));
            i.setInvoiceDate(date(r.get("invoice_date"))); i.setReceivedDate(date(r.get("received_date"))); i.setDueDate(date(r.get("due_date")));
            i.setCurrency(r.get("currency")); i.setSubtotal(money(r.get("subtotal"))); i.setGstHstAmount(money(r.get("gst_hst_amount")));
            i.setPstQstAmount(money(r.get("pst_qst_amount"))); i.setTotal(money(r.get("total"))); i.setFxRateToCad(dec(r.get("fx_rate_to_cad")));
            i.setTotalCad(money(r.get("total_cad"))); i.setDepartmentId(r.get("department_id")); i.setRequesterUserId(r.get("requester_user_id"));
            i.setPoId(r.get("po_id")); i.setContractId(r.get("contract_id")); i.setShipToProvince(r.get("ship_to_province"));
            i.setStatus(mapInvoiceStatus(r.get("status"))); i.setHistorical(true); i.setSource("HISTORY"); i.setVendorMatchMethod("HISTORY");
            i.setCreditNote(i.getTotal() != null && i.getTotal().signum() < 0);
            Instant created = i.getReceivedDate().atStartOfDay().toInstant(ZoneOffset.UTC);
            i.setCreatedAt(created); i.setUpdatedAt(created);
            return i;
        }));
        loaded.put("invoice_lines", saveAll(invoiceLines, read("invoice_lines"), r -> {
            InvoiceLine l = new InvoiceLine();
            l.setInvoiceId(r.get("invoice_id")); l.setLineNo(integer(r.get("line_no"))); l.setDescription(r.get("description"));
            l.setQuantity(dec(r.get("quantity"))); l.setUnitPrice(dec(r.get("unit_price"))); l.setAmount(money(r.get("amount")));
            l.setGlAccount(r.get("gl_account")); l.setDepartmentId(r.get("department_id")); l.setGstHstCode(r.get("gst_hst_code"));
            l.setPstQstCode(r.get("pst_qst_code")); l.setLineType(r.get("line_type"));
            return l;
        }));
        loaded.put("approvals", saveAll(approvalSteps, read("approvals"), r -> {
            ApprovalStep s = new ApprovalStep();
            s.setExternalId(r.get("approval_id")); s.setInvoiceId(r.get("invoice_id")); s.setSeq(integer(r.get("step_no")));
            s.setRequiredRole(r.get("approver_role")); s.setAssignedUserId(r.get("approver_user_id"));
            s.setDecision(r.get("action").toUpperCase(Locale.ROOT)); s.setActedBy(r.get("approver_user_id"));
            s.setActedAt(dateTime(r.get("acted_at")).toInstant(ZoneOffset.UTC)); s.setComment(r.get("comment")); s.setHistorical(true);
            return s;
        }));
        loaded.put("payments", saveAll(payments, read("payments"), r -> {
            Payment p = new Payment();
            p.setId(r.get("payment_id")); p.setInvoiceId(r.get("invoice_id")); p.setVendorId(r.get("vendor_id")); p.setMethod(r.get("method"));
            p.setScheduledDate(date(r.get("scheduled_date"))); p.setPaidDate(date(r.get("paid_date"))); p.setCurrency(r.get("currency"));
            p.setAmount(money(r.get("amount"))); p.setFxRateToCad(dec(r.get("fx_rate_to_cad"))); p.setAmountCad(money(r.get("amount_cad")));
            p.setDiscountTaken(money(r.get("discount_taken"))); p.setReference(r.get("reference")); p.setStatus(mapPaymentStatus(r.get("status")));
            p.setPreparedBy(r.get("prepared_by")); p.setReleasedBy(r.get("released_by")); p.setHistorical(true);
            return p;
        }));
        loaded.put("bank_statement", saveAll(statementLines, read("bank_statement"), r -> {
            BankStatementLine b = new BankStatementLine();
            b.setId(r.get("bank_txn_id")); b.setPostedDate(date(r.get("posted_date"))); b.setDescription(r.get("description"));
            b.setDebit(money(r.get("debit"))); b.setCredit(money(r.get("credit")));
            return b;
        }));
        loaded.put("card_transactions", saveAll(cardTxns, read("card_transactions"), r -> {
            CardTransaction c = new CardTransaction();
            c.setId(r.get("txn_id")); c.setCardId(r.get("card_id")); c.setCardholderUserId(r.get("cardholder_user_id"));
            c.setPostedDate(date(r.get("posted_date"))); c.setMerchantDescriptor(r.get("merchant_descriptor")); c.setMcc(r.get("mcc"));
            c.setAmount(money(r.get("amount"))); c.setCurrency(r.get("currency")); c.setAmountCad(money(r.get("amount_cad")));
            c.setSource(r.get("source")); c.setReceiptAttached(yes(r.get("receipt_attached"))); c.setMemo(r.get("memo"));
            return c;
        }));

        // Policies: the CSV sentences, hand-translated into structured rows
        List<Map<String, String>> policyRows = read("approval_policies");
        List<ApprovalPolicy> structured = PolicySeed.build(policyRows);
        policies.saveAll(structured);
        loaded.put("approval_policies", policyRows.size());

        loaded.put("emails", seedEmails());
        loaded.put("incoming_invoice_files", countIncomingFiles());
        flagDictionarySeed.seed();

        SeedReport report = new SeedReport(loaded, EXPECTED, System.currentTimeMillis() - t0);
        log.info("Seed finished in {} ms; counts match README: {}", report.millis(), report.allMatch());
        report.mismatches().forEach(m -> log.warn("Seed count mismatch: {}", m));
        return report;
    }

    private int seedEmails() {
        Path f = dataDir().resolve("inbox").resolve("emails.json");
        try {
            JsonNode arr = json.readTree(Files.readString(f));
            int n = 0;
            for (JsonNode e : arr) {
                EmailMessage m = new EmailMessage();
                m.setId(e.path("email_id").asText());
                m.setFromName(e.path("from_name").asText(null)); m.setFromAddress(e.path("from_address").asText(null));
                m.setToAddress(e.path("to_address").asText(null)); m.setSubject(e.path("subject").asText(null));
                m.setReceivedAt(dateTime(e.path("received_at").asText(null))); m.setBody(e.path("body").asText(null));
                List<String> att = new ArrayList<>();
                e.path("attachments").forEach(a -> att.add(a.asText()));
                m.setAttachments(String.join(",", att));
                emails.save(m);
                n++;
            }
            return n;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + f, ex);
        }
    }

    private int countIncomingFiles() {
        try (var s = Files.list(dataDir().resolve("incoming_invoices"))) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private List<Map<String, String>> read(String name) { return Csv.read(csv(name)); }

    private static <T> int saveAll(org.springframework.data.jpa.repository.JpaRepository<T, ?> repo,
                                   List<Map<String, String>> rows, java.util.function.Function<Map<String, String>, T> f) {
        List<T> list = rows.stream().map(f).toList();
        repo.saveAll(list);
        return list.size();
    }

    static InvoiceStatus mapInvoiceStatus(String s) {
        return switch (s) {
            case "paid" -> InvoiceStatus.PAID;
            case "scheduled" -> InvoiceStatus.SCHEDULED;
            case "approved" -> InvoiceStatus.APPROVED;
            case "rejected" -> InvoiceStatus.REJECTED;
            case "pending_approval" -> InvoiceStatus.PENDING_APPROVAL;
            default -> throw new IllegalArgumentException("Unknown invoice status " + s);
        };
    }

    /** Dataset "outstanding" = sent to the bank but not yet seen on the statement. */
    static String mapPaymentStatus(String s) {
        return switch (s) {
            case "settled" -> "SETTLED";
            case "outstanding" -> "SENT";
            case "scheduled" -> "SCHEDULED";
            default -> s.toUpperCase(Locale.ROOT);
        };
    }
}
