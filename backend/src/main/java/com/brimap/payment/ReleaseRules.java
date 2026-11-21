package com.brimap.payment;

import com.brimap.approval.PolicyConditionEvaluator;
import com.brimap.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Who may release a payment, as a pure function. Returns every reason the release is not allowed (empty = allowed),
 * so the same code enforces the rule on the server and explains a disabled button in the UI.
 *  P09/P10 (from the policy table): the releaser's role must be allowed for the amount (FM up to 100k, ADM any).
 *  P11: nobody who acted on any approval step of the invoice (including as a delegate, or the person delegated for).
 *  Clerks prepare, never release. Vendor must be active, its bank account verified, no open BLOCK flag.
 */
public final class ReleaseRules {
    private ReleaseRules() {}

    public record Input(Payment payment, Invoice invoice, AppUser releaser, List<ApprovalStep> steps, Vendor vendor,
                        VendorBankAccount account, List<Flag> openBlockFlags, List<ApprovalPolicy> policies,
                        List<BankChangeRequest> pendingBankChanges) {}

    public static List<String> blockers(Input in) {
        List<String> out = new ArrayList<>();
        Payment p = in.payment();
        if (!"SCHEDULED".equals(p.getStatus())) out.add("Payment is " + p.getStatus() + ", not SCHEDULED");
        AppUser u = in.releaser();
        if (u.getRole() == Role.CLK) out.add("AP clerks prepare payments; they cannot release them");
        else if (u.getRole() != Role.FM && u.getRole() != Role.ADM) out.add("Only a Finance Manager or Controller can release payments");

        BigDecimal amount = p.getAmountCad() == null ? BigDecimal.ZERO : p.getAmountCad().abs();
        Set<String> allowedRoles = new LinkedHashSet<>();
        List<String> matched = new ArrayList<>();
        for (ApprovalPolicy pol : in.policies()) {
            if (!pol.isEnabled() || !"payment_release".equals(pol.getKind()) || pol.getAddsRoles() == null || pol.getAddsRoles().isBlank()) continue;
            if (PolicyConditionEvaluator.evaluate(pol.getConditionJson(), Map.of("amountCad", amount))) {
                matched.add(pol.getId());
                for (String r : pol.getAddsRoles().split(",")) allowedRoles.add(r.trim());
            }
        }
        // Most restrictive matching rule wins: if any matched rule allows only controller_admin, FM is out.
        boolean controllerOnly = in.policies().stream().anyMatch(pol -> pol.isEnabled() && "payment_release".equals(pol.getKind())
                && "controller_admin".equals(pol.getAddsRoles()) && PolicyConditionEvaluator.evaluate(pol.getConditionJson(), Map.of("amountCad", amount)));
        if (u.getRole() == Role.FM && (controllerOnly || !allowedRoles.contains("finance_manager"))) {
            out.add("Over your release limit: CAD " + amount.toPlainString() + " needs the Controller (" + String.join(", ", matched) + ")");
        }

        for (ApprovalStep s : in.steps()) {
            if (!"APPROVED".equals(s.getDecision()) && !"REJECTED".equals(s.getDecision())) continue;
            if (u.getId().equals(s.getActedBy())) {
                out.add("You approved this invoice" + (s.getActedOnBehalfOf() == null ? "" : " (as delegate for " + s.getActedOnBehalfOf() + ")")
                        + ", so you cannot release its payment (P11)");
                break;
            }
            if (u.getId().equals(s.getActedOnBehalfOf())) {
                out.add("Your delegate approved this invoice on your behalf, so you cannot release its payment (P11)");
                break;
            }
        }
        if (in.invoice() != null && u.getId().equals(in.invoice().getRequesterUserId())) {
            out.add("You requested this invoice, so you cannot release its payment");
        }
        Vendor v = in.vendor();
        if (v == null) out.add("Vendor is not in the vendor master");
        else if (!"active".equals(v.getStatus())) out.add("Vendor " + v.getLegalName() + " is " + v.getStatus() + "; a Finance Manager must approve it first");
        if (!"CARD".equals(p.getMethod())) {
            if (in.account() == null) out.add("No bank account on file for this vendor");
            else if (!in.account().isVerified()) out.add("Vendor bank account " + in.account().masked() + " is not verified");
        }
        if (in.pendingBankChanges() != null && !in.pendingBankChanges().isEmpty()) {
            out.add("Bank change " + in.pendingBankChanges().get(0).getId() + " is waiting for verification; payments are held");
        }
        if (!in.openBlockFlags().isEmpty()) {
            out.add("Open BLOCK flags: " + String.join(", ", in.openBlockFlags().stream().map(Flag::getRuleCode).distinct().toList()));
        }
        return out;
    }
}
