package com.brimap.workflow;

import com.brimap.model.Flag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
public class FlagController {
    private final ReviewService review;

    /** CLK: WARN/INFO only. FM/ADM: any, BLOCK overrides need a note. Enforced in ReviewService. */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM')")
    public Flag resolve(@PathVariable long id, @RequestBody ReviewService.FlagResolution body) {
        return review.resolveFlag(id, body);
    }
}
