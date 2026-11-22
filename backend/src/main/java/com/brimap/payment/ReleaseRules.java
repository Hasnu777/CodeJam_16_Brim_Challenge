package com.brimap.payment;

import com.brimap.approval.PolicyConditionEvaluator;
import com.brimap.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Who may release a payment, as a pure function. Returns every reason the release is not allowed (empty = allowed),
 * so the same code enforces the rule on the server and explains a disabled button in the UI.
 *  P09/P10 (from the policy table): the releaser's role must be allowed for the amount (FM up to 100k, ADM any).
 *  P11: nobody who acted on any approval step of the invoice (including as a delegate, or the person delegated for),
 *       except the CFO for the step she approved as CFO (over $250k), logged as an exception: see sodException.
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

        boolean cfoException = sodException(in).isPresent();
        for (ApprovalStep s : in.steps()) {
            if (cfoException) break;
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

    /**
     * D-091: over $250k the CFO approves the last step (P04), and P04 + P10 + P11 together would leave nobody able to
     * release. The team chose a narrow separation-of-duties exception: the CFO (the Controller/Admin with no manager)
     * may release a payment whose approval she gave ONLY as the "cfo" step. Approving any other step (e.g. as an
     * escalated department manager), or acting as/for a delegate, still bars her. Requesting the invoice always bars.
     * Returns the explanation to write to the audit log, or empty when the exception does not apply.
     */
    public static Optional<String> sodException(Input in) {
        AppUser u = in.releaser();
        if (u == null || u.getRole() != Role.ADM || u.getManagerId() != null) return Optional.empty();
        List<ApprovalStep> mine = in.steps().stream()
                .filter(s -> "APPROVED".equals(s.getDecision()) || "REJECTED".equals(s.getDecision()))
                .filter(s -> u.getId().equals(s.getActedBy()) || u.getId().equals(s.getActedOnBehalfOf())).toList();
        if (mine.isEmpty()) return Optional.empty();
        boolean onlyCfoStep = mine.stream().allMatch(s -> "cfo".equals(s.getRequiredRole())
                && u.getId().equals(s.getActedBy()) && s.getActedOnBehalfOf() == null);
        if (!onlyCfoStep) return Optional.empty();
        return Optional.of("CFO " + u.getFullName() + " approved the final (cfo) step and releases it: separation-of-duties exception (D-091)");
    }
}
