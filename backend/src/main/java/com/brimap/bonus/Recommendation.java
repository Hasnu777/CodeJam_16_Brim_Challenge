package com.brimap.bonus;

import com.brimap.model.Flag;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.model.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bonus 3 - approval recommendation, built only from facts already computed (flags, PO/contract links, vendor
 * history). Pure function. "Approve: matches PO and contract, vendor paid 14 times" / "Look closer: ...".
 */
public final class Recommendation {
    private Recommendation() {}

    public record Result(String verdict, String headline, List<String> reasons) {}

    public static Result of(Invoice inv, List<Flag> flags, long vendorPaidCount, boolean vendorActive) {
        List<Flag> open = flags.stream().filter(Flag::isOpen).sorted(Comparator.comparing(Flag::getSeverity)).toList();
        List<Flag> acknowledged = flags.stream().filter(f -> "ACKNOWLEDGED".equals(f.getStatus()) || "OVERRIDDEN".equals(f.getStatus())).toList();
        List<String> good = new ArrayList<>(), bad = new ArrayList<>();
        if (inv.getPoId() != null && open.stream().noneMatch(f -> f.getRuleCode().equals("PO1") || f.getRuleCode().equals("P12"))) good.add("matches PO " + inv.getPoId());
        if (inv.getContractId() != null && open.stream().noneMatch(f -> f.getRuleCode().startsWith("K"))) good.add("within contract " + inv.getContractId());
        if (vendorPaidCount > 0) good.add("vendor paid " + vendorPaidCount + " time" + (vendorPaidCount == 1 ? "" : "s") + " before");
        else bad.add("first invoice from this vendor");
        if (!vendorActive) bad.add("vendor not approved yet");
        if (open.stream().noneMatch(f -> f.getSeverity() != Severity.INFO)) good.add("no open flags");
        for (Flag f : open) if (f.getSeverity() != Severity.INFO) bad.add(f.getRuleCode() + ": " + f.getMessage());
        for (Flag f : acknowledged) {
            if (f.getSeverity() == Severity.BLOCK) bad.add(f.getRuleCode() + " was overridden by " + f.getResolvedBy() + (f.getNote() == null ? "" : " (\"" + f.getNote() + "\")"));
            else if (List.of("S01", "D02", "K02", "K03", "K04", "K05", "T01", "X02").contains(f.getRuleCode())) bad.add(f.getRuleCode() + " acknowledged by the clerk: " + f.getMessage());
        }
        boolean blocking = open.stream().anyMatch(f -> f.getSeverity() == Severity.BLOCK);
        String verdict = blocking ? "HOLD" : bad.isEmpty() || (bad.size() == 1 && bad.get(0).startsWith("first invoice") && !good.isEmpty()) ? "APPROVE" : "LOOK_CLOSER";
        if (inv.getStatus() == InvoiceStatus.REJECTED || inv.getStatus() == InvoiceStatus.DUPLICATE) verdict = "HOLD";
        String headline = switch (verdict) {
            case "APPROVE" -> "Approve: " + (good.isEmpty() ? "nothing unusual found" : String.join(", ", good));
            case "HOLD" -> "Do not approve yet: " + open.stream().filter(f -> f.getSeverity() == Severity.BLOCK).map(f -> f.getRuleCode() + " " + f.getTitle()).findFirst().orElse(inv.getStatus().name());
            default -> "Look closer: " + bad.get(0).replaceFirst("^[A-Z0-9]+: ", "");
        };
        List<String> reasons = new ArrayList<>(good.stream().map(g -> "+ " + g).toList());
        reasons.addAll(bad.stream().map(b -> "- " + b).toList());
        return new Result(verdict, headline, reasons);
    }
}
