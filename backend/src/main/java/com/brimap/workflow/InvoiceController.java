package com.brimap.workflow;

import com.brimap.auth.Auth;
import com.brimap.model.Invoice;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {
    private final InvoiceQueryService query;
    private final InvoiceWorkflowService workflow;

    /** All roles; EMP sees own, APR own department (+ assigned), others all. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<InvoiceQueryService.InvoiceSummary> list(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String dept,
                                                         @RequestParam(required = false) Boolean flagged,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String vendorId,
                                                         @RequestParam(required = false) Boolean includeHistory,
                                                         @RequestParam(defaultValue = "1000") int limit) {
        return query.list(Auth.require(), status, dept, flagged, q, vendorId, includeHistory, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> detail(@PathVariable String id) {
        return query.detail(Auth.require(), id);
    }

    private final ReviewService review;

    /** Correct header fields (AP clerk, before submission). Re-runs coding and validation. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CLK')")
    public Invoice updateHeader(@PathVariable String id, @RequestBody ReviewService.HeaderUpdate body) {
        return review.updateHeader(id, body);
    }

    /** Accept/override GL, department, tax codes for one line; an override is logged with the suggestion it replaced. */
    @PutMapping("/{id}/lines/{lineNo}")
    @PreAuthorize("hasRole('CLK')")
    public com.brimap.model.InvoiceLine updateLine(@PathVariable String id, @PathVariable int lineNo, @RequestBody ReviewService.LineUpdate body) {
        return review.updateLine(id, lineNo, body);
    }

    @PostMapping("/{id}/coding/accept-all")
    @PreAuthorize("hasRole('CLK')")
    public Invoice acceptAll(@PathVariable String id) { return review.acceptAllCoding(id); }

    public record ReasonBody(String reason, String note) {}

    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM')")
    public Invoice hold(@PathVariable String id, @RequestBody ReasonBody body) {
        return workflow.hold(id, body.reason());
    }

    @PostMapping("/{id}/release-hold")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM')")
    public Invoice releaseHold(@PathVariable String id, @RequestBody(required = false) ReasonBody body) {
        return workflow.releaseHold(id, body == null ? null : body.note());
    }
}
