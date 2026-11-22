package com.brimap.vendor;

import com.brimap.model.BankChangeRequest;
import com.brimap.model.Vendor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VendorController {
    private final VendorService vendors;

    @GetMapping("/vendors")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD','APR')")
    public List<VendorService.VendorRow> list() { return vendors.list(); }

    @GetMapping("/vendors/{id}")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD','APR')")
    public Map<String, Object> detail(@PathVariable String id) { return vendors.detail(id); }

    @PostMapping("/vendors")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public Vendor onboard(@RequestBody VendorService.OnboardRequest body) { return vendors.onboard(body); }

    public record NoteBody(String note) {}

    @PostMapping("/vendors/{id}/approve")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public Vendor approve(@PathVariable String id, @RequestBody(required = false) NoteBody body) {
        return vendors.approve(id, body == null ? null : body.note());
    }

    @PostMapping("/vendors/{id}/bank-change-requests")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public BankChangeRequest requestChange(@PathVariable String id, @RequestBody VendorService.BankChangeRequestBody body) {
        return vendors.requestChange(id, body);
    }

    @PostMapping("/bank-change-requests/{id}/verify")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public BankChangeRequest verify(@PathVariable String id, @RequestBody VendorService.VerifyBody body) {
        return vendors.verify(id, body);
    }
}
