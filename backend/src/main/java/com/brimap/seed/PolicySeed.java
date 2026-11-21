package com.brimap.seed;

import com.brimap.model.ApprovalPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-translation of the dataset's 13 English policy sentences (approval_policies.csv) into structured rows.
 * The routing engine reads only the structured rows; the English text is kept in sourceText for traceability.
 */
public final class PolicySeed {
    private PolicySeed() {}

    private record Row(String id, String kind, int priority, String condition, String adds, String notes) {}

    private static final List<Row> ROWS = List.of(
            new Row("P01", "approval", 10, "{\"field\":\"amountCad\",\"op\":\"lte\",\"value\":10000}", "dept_manager",
                    "Base case. Amount = invoice total in CAD incl. tax (or the combined amount when a split is detected)."),
            new Row("P02", "approval", 20, "{\"field\":\"amountCad\",\"op\":\"gt\",\"value\":10000}", "dept_manager,finance_manager",
                    "Tiers are cumulative: every matching tier's roles are unioned, so the highest tier wins."),
            new Row("P03", "approval", 30, "{\"field\":\"amountCad\",\"op\":\"gt\",\"value\":50000}", "dept_manager,finance_manager,controller_admin", ""),
            new Row("P04", "approval", 40, "{\"field\":\"amountCad\",\"op\":\"gt\",\"value\":250000}", "dept_manager,finance_manager,controller_admin,cfo", ""),
            new Row("P05", "approval", 50,
                    "{\"any\":[{\"field\":\"vendor.status\",\"op\":\"eq\",\"value\":\"pending_review\"},"
                            + "{\"field\":\"vendor.daysSinceOnboarded\",\"op\":\"lte\",\"value\":90},"
                            + "{\"field\":\"vendor.unknown\",\"op\":\"eq\",\"value\":true}]}",
                    "finance_manager", "\"New vendor\" = onboarded within 90 days (our choice; edit the value to change it)."),
            new Row("P06", "approval", 60, "{\"field\":\"vendor.category\",\"op\":\"eq\",\"value\":\"Legal\"}", "finance_manager", ""),
            new Row("P07", "assignment", 70, "{\"always\":true}", "",
                    "Self-approval: a step assigned to the invoice requester is reassigned to the assignee's manager."),
            new Row("P08", "assignment", 80, "{\"always\":true}", "",
                    "On leave: a step assigned to someone on leave goes to their active delegate, recorded as on behalf of."),
            new Row("P09", "payment_release", 90, "{\"field\":\"amountCad\",\"op\":\"lte\",\"value\":100000}", "finance_manager,controller_admin",
                    "AP clerks prepare payments; they never release."),
            new Row("P10", "payment_release", 100, "{\"field\":\"amountCad\",\"op\":\"gt\",\"value\":100000}", "controller_admin", ""),
            new Row("P11", "payment_release", 110, "{\"always\":true}", "",
                    "Separation of duties: anyone who acted on an approval step (incl. as delegate) cannot release."),
            new Row("P12", "match", 120,
                    "{\"all\":[{\"field\":\"po.present\",\"op\":\"eq\",\"value\":true},{\"field\":\"po.overBalance\",\"op\":\"eq\",\"value\":true}]}",
                    "finance_manager", "Over-balance invoices add a finance manager step plus a WARN flag."),
            new Row("P13", "match", 130,
                    "{\"all\":[{\"field\":\"vendor.requiresPo\",\"op\":\"eq\",\"value\":true},{\"field\":\"po.present\",\"op\":\"eq\",\"value\":false}]}",
                    "", "Missing PO = BLOCK flag (hold).")
    );

    public static List<ApprovalPolicy> build(List<Map<String, String>> csvRows) {
        List<ApprovalPolicy> out = new ArrayList<>();
        for (Row r : ROWS) {
            ApprovalPolicy p = new ApprovalPolicy();
            p.setId(r.id());
            p.setKind(r.kind());
            p.setPriority(r.priority());
            p.setConditionJson(r.condition());
            p.setAddsRoles(r.adds());
            p.setEnabled(true);
            p.setNotes(r.notes());
            csvRows.stream().filter(c -> r.id().equals(c.get("rule_id"))).findFirst().ifPresent(c ->
                    p.setSourceText(c.get("condition") + " -> " + c.get("required") + (c.get("notes") == null ? "" : " (" + c.get("notes") + ")")));
            out.add(p);
        }
        return out;
    }
}
