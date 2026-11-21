package com.brimap.validation;

import com.brimap.config.AppClock;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Loads everything the rules need for one invoice. The only part of validation that touches the database. */
@Component
@RequiredArgsConstructor
public class InvoiceContextFactory {
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceTaxRepository taxes;
    private final VendorRepository vendors;
    private final VendorBankAccountRepository bankAccounts;
    private final BankChangeRequestRepository bankChanges;
    private final ContractRepository contracts;
    private final PurchaseOrderRepository pos;
    private final StoredDocumentRepository docs;
    private final CardTransactionRepository cards;
    private final TaxCodeRepository taxCodes;
    private final ApprovalPolicyRepository policies;
    private final ObjectMapper json;
    private final AppClock clock;

    private static final Set<InvoiceStatus> NOT_BILLED = Set.of(InvoiceStatus.REJECTED, InvoiceStatus.DUPLICATE);

    public InvoiceContext build(Invoice inv) {
        List<Vendor> allVendors = vendors.findAll();
        List<Invoice> others = invoices.findAll().stream().filter(o -> !o.getId().equals(inv.getId())).toList();
        Map<Long, String> shaByDoc = docs.findAll().stream().collect(Collectors.toMap(StoredDocument::getId, StoredDocument::getSha256));
        Map<String, String> shaByInvoice = new HashMap<>();
        for (Invoice o : others) if (o.getDocumentId() != null && shaByDoc.containsKey(o.getDocumentId())) shaByInvoice.put(o.getId(), shaByDoc.get(o.getDocumentId()));
        PurchaseOrder po = inv.getPoId() == null ? null : pos.findById(inv.getPoId()).orElse(null);
        BigDecimal consumed = po == null ? BigDecimal.ZERO : others.stream()
                .filter(o -> po.getId().equals(o.getPoId()) && !NOT_BILLED.contains(o.getStatus()) && o.getSubtotal() != null)
                .map(Invoice::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        return InvoiceContext.builder()
                .invoice(inv)
                .lines(lines.findByInvoiceIdOrderByLineNo(inv.getId()))
                .taxes(taxes.findByInvoiceId(inv.getId()))
                .vendor(inv.getVendorId() == null ? null : allVendors.stream().filter(v -> v.getId().equals(inv.getVendorId())).findFirst().orElse(null))
                .vendors(allVendors)
                .bankAccounts(bankAccounts.findAll())
                .bankChanges(bankChanges.findAll())
                .contracts(contracts.findAll())
                .po(po)
                .poConsumed(consumed)
                .otherInvoices(others)
                .documentShaByInvoice(shaByInvoice)
                .documentSha(inv.getDocumentId() == null ? null : shaByDoc.get(inv.getDocumentId()))
                .cardTransactions(cards.findAll())
                .taxCodes(taxCodes.findAll().stream().collect(Collectors.toMap(TaxCode::getCode, t -> t)))
                .today(clock.today())
                .approvalThresholds(approvalThresholds())
                .build();
    }

    /** Every "amountCad > N" threshold in the enabled approval policies (P02-P04 by default: 10k, 50k, 250k). */
    public List<BigDecimal> approvalThresholds() {
        Set<BigDecimal> out = new TreeSet<>();
        for (ApprovalPolicy p : policies.findAll()) {
            if (!p.isEnabled() || !"approval".equals(p.getKind()) || p.getConditionJson() == null) continue;
            try {
                collect(json.readTree(p.getConditionJson()), out);
            } catch (Exception ignored) { /* invalid JSON is rejected at save time */ }
        }
        return new ArrayList<>(out);
    }

    private static void collect(JsonNode n, Set<BigDecimal> out) {
        if (n == null) return;
        if ("amountCad".equals(n.path("field").asText()) && ("gt".equals(n.path("op").asText()) || "gte".equals(n.path("op").asText()))) {
            out.add(n.path("value").decimalValue());
        }
        n.elements().forEachRemaining(c -> collect(c, out));
    }
}
