package com.brimap.extraction;

import com.brimap.duplicates.InvoiceNumbers;
import com.brimap.model.*;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.brimap.extraction.InvoiceNormalizer.*;

/**
 * Turns an untrusted extraction into invoice fields, lines and tax rows. Deterministic: normalization, vendor
 * matching, ship-to province, FX conversion, PO/contract lookup, and department/requester inference from history.
 * Anything it can't establish is left null and listed in lowConfidenceFields; rules turn that into flags.
 */
@Component
@RequiredArgsConstructor
public class InvoiceAssembler {
    public static final double LOW_CONFIDENCE = 0.6;

    private final VendorRepository vendors;
    private final PurchaseOrderRepository pos;
    private final ContractRepository contracts;
    private final FxRateRepository fx;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lineRepo;

    public record Assembled(List<InvoiceLine> lines, List<InvoiceTax> taxes, VendorMatcher.Match vendorMatch, Set<String> lowConfidence) {}

    public Assembled apply(Invoice inv, ExtractedInvoice x, String senderEmail) {
        Set<String> low = new LinkedHashSet<>();
        if (x == null) {
            low.addAll(List.of("vendor", "invoiceNumber", "invoiceDate", "total"));
            inv.setLowConfidenceFields(String.join(",", low));
            inv.setVendorMatchMethod("NONE");
            return new Assembled(List.of(), List.of(), VendorMatcher.Match.none("Manual entry"), low);
        }
        // Header
        inv.setVendorNameRaw(blankToNull(x.vendorName()));
        inv.setVendorInvoiceNumber(blankToNull(x.invoiceNumber()));
        inv.setNormalizedInvoiceNumber(InvoiceNumbers.normalize(inv.getVendorInvoiceNumber()));
        inv.setInvoiceDate(date(x.invoiceDate()));
        inv.setDueDate(date(x.dueDate()));
        inv.setPaymentTermsText(blankToNull(x.paymentTerms()));
        inv.setCurrency(blankToNull(x.currency()) != null && x.currency().trim().length() == 3
                ? x.currency().trim().toUpperCase(Locale.ROOT) : currency(x.currency(), x.total()));
        inv.setSubtotal(money(x.subtotal()));
        inv.setTotal(money(x.total()));
        inv.setGstNumberOnInvoice(VendorMatcher.canonicalGst(x.vendorGstNumber()) != null
                ? VendorMatcher.canonicalGst(x.vendorGstNumber()) : blankToNull(x.vendorGstNumber()));
        inv.setQstNumberOnInvoice(blankToNull(digits(x.vendorQstNumber())));
        inv.setBillToText(join(x.billToName(), x.billToAddress()));
        inv.setRemitInstitution(blankToNull(digits(x.remitInstitution())));
        inv.setRemitTransit(blankToNull(digits(x.remitTransit())));
        inv.setRemitAccount(blankToNull(digits(x.remitAccount())));
        inv.setCreditNote("credit_note".equalsIgnoreCase(x.documentType()) || (inv.getTotal() != null && inv.getTotal().signum() < 0));
        inv.setShipToProvince(shipToProvince(inv.getBillToText()));

        // Taxes
        List<InvoiceTax> taxes = new ArrayList<>();
        BigDecimal gstHst = BigDecimal.ZERO, pstQst = BigDecimal.ZERO;
        for (ExtractedInvoice.Tax t : Optional.ofNullable(x.taxes()).orElse(List.of())) {
            BigDecimal amt = money(t.amount());
            if (amt == null) continue;
            InvoiceTax it = new InvoiceTax();
            it.setLabel(t.label());
            it.setTaxCode(taxCode(t.label(), t.ratePercent()));
            it.setRate(rateFraction(t.ratePercent()));
            it.setAmount(amt);
            taxes.add(it);
            if ("QST9975".equals(it.getTaxCode()) || "PST7_BC".equals(it.getTaxCode())) pstQst = pstQst.add(amt);
            else gstHst = gstHst.add(amt);
        }
        inv.setGstHstAmount(gstHst.setScale(2, RoundingMode.HALF_UP));
        inv.setPstQstAmount(pstQst.setScale(2, RoundingMode.HALF_UP));
        if (inv.getSubtotal() == null && inv.getTotal() != null) inv.setSubtotal(inv.getTotal().subtract(gstHst).subtract(pstQst));

        // Vendor
        List<Vendor> all = vendors.findAll();
        VendorMatcher.Match match = VendorMatcher.match(all, x.vendorGstNumber(), senderEmail, x.vendorName());
        inv.setVendorId(match.vendorId());
        inv.setVendorMatchMethod(match.method());
        Vendor vendor = match.vendorId() == null ? null : all.stream().filter(v -> v.getId().equals(match.vendorId())).findFirst().orElse(null);

        // FX
        applyFx(inv);

        // Due date from terms when missing
        if (inv.getDueDate() == null && inv.getInvoiceDate() != null && vendor != null && vendor.getPaymentTermsDays() != null) {
            inv.setDueDate(inv.getInvoiceDate().plusDays(vendor.getPaymentTermsDays()));
        }

        // PO, contract, department, requester
        inferPoAndOwner(inv, x.poNumber(), vendor);

        // Lines (tax codes per line come from the invoice's tax rows)
        String gstCode = taxes.stream().map(InvoiceTax::getTaxCode).filter(c -> "GST5".equals(c) || "HST13".equals(c)).findFirst()
                .orElse(vendor != null && "US".equals(vendor.getCountry()) ? "FOREIGN" : null);
        String pstCode = taxes.stream().map(InvoiceTax::getTaxCode).filter(c -> "QST9975".equals(c) || "PST7_BC".equals(c)).findFirst().orElse(null);
        String typicalLineType = vendor == null ? "services" : typicalLineType(vendor.getId());
        List<InvoiceLine> lines = new ArrayList<>();
        int n = 1;
        for (ExtractedInvoice.Line l : Optional.ofNullable(x.lines()).orElse(List.of())) {
            InvoiceLine il = new InvoiceLine();
            il.setLineNo(n++);
            il.setDescription(l.description());
            il.setQuantity(amount(l.quantity()));
            il.setUnitPrice(amount(l.unitPrice()));
            il.setAmount(money(l.amount()));
            if (il.getAmount() == null && il.getQuantity() != null && il.getUnitPrice() != null) {
                il.setAmount(il.getQuantity().multiply(il.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
                low.add("lines");
            }
            il.setGstHstCode(gstCode);
            il.setPstQstCode(pstCode);
            il.setLineType(typicalLineType);
            il.setDepartmentId(inv.getDepartmentId());
            lines.add(il);
        }

        // Confidence
        var c = x.confidence();
        if (c != null) {
            if (c.vendorName() < LOW_CONFIDENCE) low.add("vendor");
            if (c.invoiceNumber() < LOW_CONFIDENCE) low.add("invoiceNumber");
            if (c.invoiceDate() < LOW_CONFIDENCE) low.add("invoiceDate");
            if (c.dueDate() < LOW_CONFIDENCE) low.add("dueDate");
            if (c.total() < LOW_CONFIDENCE) low.add("total");
            if (c.lines() < LOW_CONFIDENCE) low.add("lines");
            if (c.taxes() < LOW_CONFIDENCE) low.add("taxes");
        }
        if (inv.getVendorInvoiceNumber() == null) low.add("invoiceNumber");
        if (inv.getInvoiceDate() == null) low.add("invoiceDate");
        if (inv.getTotal() == null) low.add("total");
        if (inv.getVendorId() == null) low.add("vendor");
        if (inv.getDepartmentId() == null) low.add("department");
        if (inv.getShipToProvince() == null) low.add("shipToProvince");
        inv.setLowConfidenceFields(low.isEmpty() ? null : String.join(",", low));
        return new Assembled(lines, taxes, match, low);
    }

    /** Converts to CAD at the invoice-date rate (or the previous available day, which rule FX2 reports). */
    public void applyFx(Invoice inv) {
        if (inv.getTotal() == null) return;
        if (inv.getCurrency() == null || "CAD".equals(inv.getCurrency())) {
            inv.setFxRateToCad(BigDecimal.ONE);
            inv.setFxRateDate(null);
            inv.setTotalCad(inv.getTotal());
            return;
        }
        LocalDate d = inv.getInvoiceDate() != null ? inv.getInvoiceDate() : LocalDate.now();
        fx.findFirstByDateLessThanEqualOrderByDateDesc(d).ifPresent(r -> {
            inv.setFxRateToCad(r.getUsdToCad());
            inv.setFxRateDate(r.getDate());
            inv.setTotalCad(inv.getTotal().multiply(r.getUsdToCad()).setScale(2, RoundingMode.HALF_UP));
        });
    }

    /** Harborline's 4 offices -> province; else a province code in the address. */
    public static String shipToProvince(String billTo) {
        if (billTo == null) return null;
        String s = stripAccents(billTo).toUpperCase(Locale.ROOT);
        if (s.contains("TORONTO")) return "ON";
        if (s.contains("VANCOUVER")) return "BC";
        if (s.contains("CALGARY")) return "AB";
        if (s.contains("MONTREAL")) return "QC";
        var m = java.util.regex.Pattern.compile("\\b(ON|QC|BC|AB|MB|SK|NS|NB|NL|PE|YT|NT|NU)\\b\\s+[A-Z]\\d[A-Z]").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    void inferPoAndOwner(Invoice inv, String poRaw, Vendor vendor) {
        String poNum = blankToNull(poRaw);
        if (poNum != null) {
            String key = poNum.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
            pos.findAll().stream().filter(p -> p.getId().replaceAll("[^0-9A-Za-z]", "").equalsIgnoreCase(key)
                            || p.getId().endsWith(poNum.trim()))
                    .findFirst().ifPresentOrElse(p -> {
                        inv.setPoId(p.getId());
                        inv.setDepartmentId(p.getDepartmentId());
                        inv.setRequesterUserId(p.getRequesterUserId());
                    }, () -> inv.setPoId(poNum)); // unknown PO kept as printed; rule PO1 flags it
        }
        if (vendor == null) return;
        LocalDate d = inv.getInvoiceDate() == null ? LocalDate.now() : inv.getInvoiceDate();
        contracts.findByVendorId(vendor.getId()).stream()
                .sorted(Comparator.comparing(Contract::getStartDate).reversed())
                .filter(c -> !c.getStartDate().isAfter(d))
                .findFirst().ifPresent(c -> inv.setContractId(c.getId()));
        if (inv.getDepartmentId() == null || inv.getRequesterUserId() == null) {
            // Most frequent (department, requester) for this vendor in history: "who usually buys from them".
            List<Invoice> hist = invoices.findByVendorId(vendor.getId()).stream().filter(Invoice::isHistorical).toList();
            if (!hist.isEmpty()) {
                String dept = mode(hist, Invoice::getDepartmentId);
                if (inv.getDepartmentId() == null) inv.setDepartmentId(dept);
                if (inv.getRequesterUserId() == null) {
                    String deptFinal = inv.getDepartmentId();
                    inv.setRequesterUserId(mode(hist.stream().filter(h -> Objects.equals(h.getDepartmentId(), deptFinal)).toList(),
                            Invoice::getRequesterUserId));
                }
            }
        }
    }

    private String typicalLineType(String vendorId) {
        List<String> ids = invoices.findByVendorId(vendorId).stream().filter(Invoice::isHistorical).map(Invoice::getId).toList();
        Map<String, Long> counts = ids.stream().flatMap(id -> lineRepo.findByInvoiceIdOrderByLineNo(id).stream())
                .collect(Collectors.groupingBy(l -> Optional.ofNullable(l.getLineType()).orElse("services"), Collectors.counting()));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("services");
    }

    private static <T> String mode(List<T> rows, Function<T, String> f) {
        return rows.stream().map(f).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static String join(String a, String b) {
        String s = (Optional.ofNullable(blankToNull(a)).orElse("") + "\n" + Optional.ofNullable(blankToNull(b)).orElse("")).strip();
        return s.isEmpty() ? null : s;
    }
}
