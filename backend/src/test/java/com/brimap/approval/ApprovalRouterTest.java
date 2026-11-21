package com.brimap.approval;

import com.brimap.model.*;
import com.brimap.seed.PolicySeed;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Routing on the real people from users.csv / departments.csv and the structured policy table. */
class ApprovalRouterTest {
    static final List<ApprovalPolicy> POLICIES = PolicySeed.build(List.of());

    static AppUser u(String id, String name, String dept, String mgr, Role role, String limit, UserStatus st, String delegate) {
        AppUser a = new AppUser();
        a.setId(id); a.setFullName(name); a.setDepartmentId(dept); a.setManagerId(mgr); a.setRole(role);
        a.setApprovalLimitCad(new BigDecimal(limit)); a.setStatus(st); a.setDelegateUserId(delegate);
        return a;
    }

    static Department d(String id, String mgr) {
        Department x = new Department();
        x.setId(id); x.setManagerUserId(mgr);
        return x;
    }

    static ApprovalRouter.Directory dir() {
        Map<String, AppUser> users = new HashMap<>();
        for (AppUser a : List.of(
                u("U001", "Priya Raman", "D08", null, Role.ADM, "500000", UserStatus.active, null),
                u("U002", "Marcus Webb", "D04", "U001", Role.ADM, "250000", UserStatus.active, null),
                u("U003", "Elise Tremblay", "D04", "U002", Role.FM, "50000", UserStatus.active, null),
                u("U004", "Jordan Okafor", "D04", "U003", Role.CLK, "0", UserStatus.active, null),
                u("U007", "Dev Malhotra", "D01", "U001", Role.APR, "10000", UserStatus.active, null),
                u("U010", "Wen Zhao", "D05", "U001", Role.APR, "10000", UserStatus.active, null),
                u("U012", "Liam O'Connor", "D07", "U001", Role.APR, "10000", UserStatus.on_leave, "U003"),
                u("U015", "Noah Park", "D01", "U007", Role.EMP, "0", UserStatus.terminated, null),
                u("U019", "Stefan Haddad", "D01", "U007", Role.EMP, "0", UserStatus.active, null),
                u("U037", "Kavya Morrison", "D07", "U012", Role.EMP, "0", UserStatus.active, null))) users.put(a.getId(), a);
        Map<String, Department> depts = Map.of("D01", d("D01", "U007"), "D05", d("D05", "U010"), "D07", d("D07", "U012"),
                "D04", d("D04", "U002"), "D99", d("D99", "U015"));
        Delegation del = new Delegation();
        del.setFromUserId("U012"); del.setToUserId("U003"); del.setStartDate(LocalDate.of(2026, 10, 20));
        return new ApprovalRouter.Directory(users, depts, List.of(del), LocalDate.of(2026, 11, 2));
    }

    static Map<String, Object> facts(String amount, String vendorStatus, String category) {
        Map<String, Object> f = new HashMap<>();
        f.put("amountCad", new BigDecimal(amount));
        f.put("vendor.unknown", vendorStatus == null);
        if (vendorStatus != null) { f.put("vendor.status", vendorStatus); f.put("vendor.category", category); f.put("vendor.daysSinceOnboarded", 900L); }
        f.put("po.present", false);
        f.put("po.overBalance", false);
        return f;
    }

    @Test
    void prdWorkedExample_12000FacilitiesInvoice_delegateThenEscalation() {
        var r = ApprovalRouter.route(POLICIES, facts("12000", "active", "Rent & Property"), "D07", "U037", dir());
        assertThat(r.matchedPolicies()).containsExactly("P02");
        assertThat(r.steps()).hasSize(2);
        var s1 = r.steps().get(0);
        assertThat(s1.role()).isEqualTo("dept_manager");
        assertThat(s1.assigneeId()).isEqualTo("U003");         // Liam on leave -> delegate Elise
        assertThat(s1.onBehalfOfId()).isEqualTo("U012");
        assertThat(s1.notes()).anyMatch(n -> n.contains("on leave") && n.contains("P08"));
        var s2 = r.steps().get(1);
        assertThat(s2.role()).isEqualTo("finance_manager");
        assertThat(s2.assigneeId()).isEqualTo("U002");         // Elise already on the chain -> her manager Marcus
        assertThat(s2.notes()).anyMatch(n -> n.contains("already approves an earlier step"));
    }

    @Test
    void baseCase_under10k_deptManagerOnly() {
        var r = ApprovalRouter.route(POLICIES, facts("4719.28", "active", "Utilities"), "D01", "U019", dir());
        assertThat(r.steps()).extracting(ApprovalRouter.Step::assigneeId).containsExactly("U007");
    }

    @Test
    void orionSplit_routedAtCombined14543_addsFinanceManager() {
        var r = ApprovalRouter.route(POLICIES, facts("14543.10", "active", "Consulting"), "D05", "U019", dir());
        assertThat(r.steps()).extracting(ApprovalRouter.Step::role).containsExactly("dept_manager", "finance_manager");
        assertThat(r.steps()).extracting(ApprovalRouter.Step::assigneeId).containsExactly("U010", "U003");
    }

    @Test
    void tiersAreCumulative_over250k_addsControllerAndCfo() {
        var r = ApprovalRouter.route(POLICIES, facts("300000", "active", "Rent & Property"), "D01", "U019", dir());
        assertThat(r.steps()).extracting(ApprovalRouter.Step::role).containsExactly("dept_manager", "finance_manager", "controller_admin", "cfo");
        assertThat(r.steps()).extracting(ApprovalRouter.Step::assigneeId).containsExactly("U007", "U003", "U002", "U001");
    }

    @Test
    void p05_pendingOrUnknownVendor_and_p06_legal_addFinanceManager() {
        var riverbend = ApprovalRouter.route(POLICIES, facts("2860", "pending_review", "Events & Catering"), "D01", "U019", dir());
        assertThat(riverbend.matchedPolicies()).contains("P01", "P05");
        assertThat(riverbend.steps()).extracting(ApprovalRouter.Step::role).containsExactly("dept_manager", "finance_manager");
        var unknown = ApprovalRouter.route(POLICIES, facts("1407.74", null, null), "D01", "U019", dir());
        assertThat(unknown.matchedPolicies()).contains("P05");
        var legal = ApprovalRouter.route(POLICIES, facts("6452.30", "active", "Legal"), "D04", "U004", dir());
        assertThat(legal.matchedPolicies()).contains("P06");
        assertThat(legal.steps()).extracting(ApprovalRouter.Step::assigneeId).containsExactly("U002", "U003");
    }

    @Test
    void p07_selfApproval_escalatesToManager() {
        // Dev Malhotra (D01 manager) requested it himself
        var r = ApprovalRouter.route(POLICIES, facts("900", "active", "Fuel & Fleet"), "D01", "U007", dir());
        assertThat(r.steps().get(0).assigneeId()).isEqualTo("U001");
        assertThat(r.steps().get(0).notes()).anyMatch(n -> n.contains("P07"));
    }

    @Test
    void requesterOnLeave_isNotApprovedByTheirOwnDelegate_escalatesToManager() {
        // Liam (D07 manager, on leave, delegate Elise) requested a Facilities bill himself. Elise acting "on behalf of
        // Liam" would be Liam approving his own spending by proxy (P07): it must go to Liam's manager instead.
        var r = ApprovalRouter.route(POLICIES, facts("900", "active", "Rent & Property"), "D07", "U012", dir());
        var s1 = r.steps().get(0);
        assertThat(s1.assigneeId()).isEqualTo("U001");
        assertThat(s1.onBehalfOfId()).isNull();
        assertThat(s1.notes()).anyMatch(n -> n.contains("P07"));
    }

    @Test
    void reroute_peopleWhoAlreadyActedAreNotReused() {
        // Worked example, step 1 already approved by Elise on behalf of Liam. Re-routing the remaining FM step must not
        // land on Elise or Liam again.
        var r = ApprovalRouter.route(POLICIES, facts("12000", "active", "Rent & Property"), "D07", "U037", dir(), Set.of("U003", "U012"), Set.of("finance_manager"));
        assertThat(r.steps()).filteredOn(s -> s.role().equals("finance_manager")).extracting(ApprovalRouter.Step::assigneeId).containsExactly("U002");
    }

    @Test
    void reroute_afterAnApproverIsTerminated_goesToTheirManager() {
        var d = dir();
        d.users().get("U002").setStatus(UserStatus.terminated);   // Marcus left after the chain was snapshotted
        var r = ApprovalRouter.route(POLICIES, facts("12000", "active", "Rent & Property"), "D07", "U037", d, Set.of("U003", "U012"), Set.of("finance_manager"));
        assertThat(r.steps()).filteredOn(s -> s.role().equals("finance_manager")).extracting(ApprovalRouter.Step::assigneeId).containsExactly("U001");
    }

    @Test
    void terminatedAssigneeIsSkippedToManager() {
        var r = ApprovalRouter.route(POLICIES, facts("500", "active", "Freight & Carriers"), "D99", "U019", dir());
        assertThat(r.steps().get(0).assigneeId()).isEqualTo("U007");
        assertThat(r.steps().get(0).notes()).anyMatch(n -> n.contains("terminated"));
    }

    @Test
    void finalStepLimitEscalatesUpTheChain() {
        // P02 rule switched off: a 30k invoice would end at the dept manager (10k limit) -> escalates to the CFO
        List<ApprovalPolicy> onlyBase = new ArrayList<>();
        for (ApprovalPolicy p : POLICIES) {
            ApprovalPolicy c = new ApprovalPolicy();
            c.setId(p.getId()); c.setKind(p.getKind()); c.setPriority(p.getPriority()); c.setConditionJson(p.getConditionJson());
            c.setAddsRoles(p.getAddsRoles()); c.setEnabled(!p.getId().equals("P02"));
            if (p.getId().equals("P01")) c.setConditionJson("{\"always\":true}");
            onlyBase.add(c);
        }
        var r = ApprovalRouter.route(onlyBase, facts("30000", "active", "Freight & Carriers"), "D01", "U019", dir());
        assertThat(r.steps()).hasSize(1);
        assertThat(r.steps().get(0).assigneeId()).isEqualTo("U001");
        assertThat(r.steps().get(0).notes()).anyMatch(n -> n.contains("limit"));
    }

    @Test
    void noDepartmentIsAProblem() {
        var r = ApprovalRouter.route(POLICIES, facts("500", "active", "Legal"), null, "U019", dir());
        assertThat(r.problems()).anyMatch(p -> p.contains("No department"));
    }

    @Test
    void conditionEvaluator() {
        Map<String, Object> f = Map.of("amountCad", new BigDecimal("10000.00"), "vendor.status", "active");
        assertThat(PolicyConditionEvaluator.evaluate("{\"field\":\"amountCad\",\"op\":\"lte\",\"value\":10000}", f)).isTrue();
        assertThat(PolicyConditionEvaluator.evaluate("{\"field\":\"amountCad\",\"op\":\"gt\",\"value\":10000}", f)).isFalse();
        assertThat(PolicyConditionEvaluator.evaluate("{\"any\":[{\"field\":\"vendor.status\",\"op\":\"eq\",\"value\":\"pending_review\"},"
                + "{\"field\":\"missing\",\"op\":\"gt\",\"value\":1}]}", f)).isFalse();
        assertThat(PolicyConditionEvaluator.evaluate("{\"not\":{\"field\":\"vendor.status\",\"op\":\"in\",\"value\":[\"on_hold\",\"pending_review\"]}}", f)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PolicyConditionEvaluator.validate("{\"field\":\"amountCad\",\"op\":\"gt\",\"value\":\"x\"}"))
                .hasMessageContaining("numeric");
    }
}
