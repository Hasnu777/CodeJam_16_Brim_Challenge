package com.brimap.approval;

import com.brimap.model.Invoice;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApprovalController {
    private final ApprovalService approvals;

    /** Clerk done: validation + routing -> PENDING_APPROVAL. Blocked by unresolved BLOCK flags or unacknowledged warnings. */
    @PostMapping("/invoices/{id}/submit")
    @PreAuthorize("hasRole('CLK')")
    public Invoice submit(@PathVariable String id) { return approvals.submit(id); }

    /** Must be the current step's assignee or their active delegate; never the requester. */
    @PostMapping("/invoices/{id}/approve")
    @PreAuthorize("hasAnyRole('APR','FM','ADM')")
    public Invoice approve(@PathVariable String id, @RequestBody(required = false) ApprovalService.Decision body) {
        return approvals.approve(id, body == null ? null : body.comment());
    }

    @PostMapping("/invoices/{id}/reject")
    @PreAuthorize("hasAnyRole('APR','FM','ADM')")
    public Invoice reject(@PathVariable String id, @RequestBody ApprovalService.Decision body) {
        return approvals.reject(id, body.reason() != null ? body.reason() : body.comment());
    }

    /** REJECTED -> IN_REVIEW so the clerk can fix and resubmit; resubmitting starts a fresh approval round. */
    @PostMapping("/invoices/{id}/reopen")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public Invoice reopen(@PathVariable String id, @RequestBody ApprovalService.Decision body) {
        return approvals.reopen(id, body.reason() != null ? body.reason() : body.comment());
    }

    /** Admin: re-resolve the still-pending steps against today's people (approver left, on leave, role changed). */
    @PostMapping("/invoices/{id}/reroute")
    @PreAuthorize("hasRole('ADM')")
    public Invoice reroute(@PathVariable String id, @RequestBody ApprovalService.Decision body) {
        return approvals.reroute(id, body.reason() != null ? body.reason() : body.comment());
    }

    @GetMapping("/approvals/mine")
    @PreAuthorize("hasAnyRole('APR','FM','ADM')")
    public List<ApprovalService.QueueItem> mine() { return approvals.mine(); }
}
