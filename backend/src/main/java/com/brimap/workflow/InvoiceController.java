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

    private final com.brimap.purchasing.PoSuggestionService poSuggestions;
    private final InvoiceAccess access;

    /** Open POs of this invoice's vendor, best match first, each with the reasons and warnings behind its rank. */
    @GetMapping("/{id}/po-suggestions")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD','APR')")
    public List<com.brimap.purchasing.PoSuggester.Suggestion> poSuggestions(@PathVariable String id) {
        com.brimap.model.Invoice inv = workflow.load(id);
        access.requireView(Auth.require(), inv);
        return poSuggestions.suggest(inv);
    }

    public record LinkPoBody(String poId) {}

    /** Link a PO the clerk picked. Audited as PO_LINKED; re-validation clears P13. */
    @PostMapping("/{id}/po")
    @PreAuthorize("hasRole('CLK')")
    public Invoice linkPo(@PathVariable String id, @RequestBody LinkPoBody body) {
        return review.linkPo(id, body.poId());
    }

    public record ReasonBody(String reason, String note) {}

    private final com.brimap.validation.RevalidationService revalidation;

    /** Delete a not-yet-approved bill (duplicate upload, wrong file). EMP: own uploads only. Audited (D-101). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMP','CLK','ADM')")
    public Map<String, Object> delete(@PathVariable String id, @RequestBody ReasonBody body) {
        workflow.delete(id, body == null ? null : body.reason());
        // Other open bills may have been flagged against this one (e.g. as its duplicate): re-check them.
        revalidation.revalidateOpen();
        return Map.of("deleted", id);
    }

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
