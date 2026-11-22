package com.brimap.payment;

import com.brimap.model.Payment;
import com.brimap.model.PaymentBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;

    /** Dashboard. Each SCHEDULED row carries the reasons the current user can't release it (for disabled buttons). */
    @GetMapping
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD')")
    public List<PaymentService.PaymentRow> list(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "false") boolean includeHistory) {
        return payments.list(status, includeHistory);
    }

    public record PrepareRequest(List<String> invoiceIds) {}

    @PostMapping("/prepare")
    @PreAuthorize("hasRole('CLK')")
    public List<Payment> prepare(@RequestBody PrepareRequest body) { return payments.prepare(body.invoiceIds()); }

    /** P09/P10 limits and P11 separation of duties are enforced in ReleaseRules; violations are 403 with the reason. */
    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public Payment release(@PathVariable String id) { return payments.release(id); }

    /** After a verified bank change: point a scheduled payment at the vendor's current account (it still needs a release). */
    @PostMapping("/{id}/reprepare")
    @PreAuthorize("hasRole('CLK')")
    public Payment reprepare(@PathVariable String id) { return payments.reprepare(id); }
    public record MethodRequest(String method) {}

    /** Brim card optimizer: move a scheduled payment to the AP card (or back to EFT). Audited. */
    @PostMapping("/{id}/method")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM')")
    public Payment changeMethod(@PathVariable String id, @RequestBody MethodRequest body) { return payments.changeMethod(id, body.method()); }

    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public PaymentBatch batch() { return payments.batch(); }

    @GetMapping("/batches")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD')")
    public List<PaymentBatch> batches() { return payments.batches(); }

    @GetMapping(value = "/batches/{id}/file", produces = "text/csv")
    @PreAuthorize("hasAnyRole('FM','ADM','AUD')")
    public ResponseEntity<String> batchFile(@PathVariable String id) {
        PaymentBatch b = payments.batches().stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new com.brimap.common.NotFoundException("Batch " + id));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + id + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(b.getFileContent());
    }

    public record SettleRequest(LocalDate paidDate) {}

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public Payment settle(@PathVariable String id, @RequestBody(required = false) SettleRequest body) {
        return payments.settle(id, body == null ? null : body.paidDate());
    }

    public record FailRequest(String reason) {}

    @PostMapping("/{id}/fail")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public Payment fail(@PathVariable String id, @RequestBody FailRequest body) { return payments.fail(id, body.reason()); }
}
