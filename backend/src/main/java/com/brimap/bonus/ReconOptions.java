package com.brimap.bonus;

import java.util.List;
import java.util.Optional;

/**
 * What a Finance Manager can do with a reconciliation leftover (D-109). Each option says whether this app carries it
 * out completely ("app": the decision is the whole job) or whether the real work happens in a system the dataset does
 * not give us ("external": bank disputes, stop-payments, general-ledger journals). External options are recorded as
 * the decision, with what would happen next, so the model is complete even though the integration is out of scope.
 */
public final class ReconOptions {
    private ReconOptions() {}

    public enum Kind { LINE, PAYMENT }

    public record Option(String code, Kind kind, String label, String description, String scope, String outOfScope,
                         boolean closes, boolean noteRequired) {}

    public static final List<Option> ALL = List.of(
            // Statement lines with no payment behind them.
            new Option("BANK_CHARGE", Kind.LINE, "Bank charge",
                    "A fee the bank took (account, wire). Expensed to bank charges; no bill expected.", "external",
                    "Posting the journal entry to the bank-charges GL account happens in the general ledger, which this app does not keep.",
                    true, false),
            new Option("BILL_FOUND", Kind.LINE, "Bill found",
                    "A real supplier pulls this by pre-authorized debit. Note the supplier; upload their bill so it is coded and approved.", "app",
                    null, true, true),
            new Option("DISPUTED", Kind.LINE, "Disputed with the bank",
                    "Nobody authorized this debit, or the bank took the wrong amount. Report it to the bank for reversal or correction.", "external",
                    "Filing the dispute and receiving the reversal or refund go through the bank; the dataset has no bank connection.",
                    true, true),
            new Option("INVESTIGATING", Kind.LINE, "Under investigation",
                    "Not explained yet. Stays on the list, marked, until someone records the outcome.", "app", null, false, true),
            // Payments we recorded that the bank statement does not show.
            new Option("WAIT_NEXT_STATEMENT", Kind.PAYMENT, "Expected next statement",
                    "Sent near or after the statement end; it should clear on the next one. Check again then.", "app", null, true, false),
            new Option("VENDOR_CONFIRMED", Kind.PAYMENT, "Vendor confirmed receipt",
                    "The vendor says the money or cheque arrived; the bank should show it soon.", "app", null, true, true),
            new Option("STOP_AND_REISSUE", Kind.PAYMENT, "Stop and reissue",
                    "The cheque never arrived or was lost. Stop payment on it and pay the vendor again.", "external",
                    "The stop-payment is placed with the bank and a new cheque is cut from cheque stock; neither exists in the dataset. "
                            + "In the app, the replacement would go through the normal failed-payment retry.",
                    true, true),
            new Option("INVESTIGATING", Kind.PAYMENT, "Under investigation",
                    "Not explained yet. Stays on the list, marked, until someone records the outcome.", "app", null, false, true)
    );

    public static Optional<Option> find(Kind kind, String code) {
        return ALL.stream().filter(o -> o.kind() == kind && o.code().equals(code)).findFirst();
    }
}
