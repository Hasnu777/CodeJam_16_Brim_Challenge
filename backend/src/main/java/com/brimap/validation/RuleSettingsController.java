package com.brimap.validation;

import com.brimap.audit.AuditService;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.model.FlagDictionary;
import com.brimap.model.Severity;
import com.brimap.repository.FlagDictionaryRepository;
import com.brimap.seed.FlagDictionarySeed;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Policies > Validation Rules (D-108). The checks themselves are code (tested); which ones run and how severe their
 * finding is are data an Admin can change. Every change needs a reason, is audited, and re-checks the open bills.
 * Core fraud and duplicate controls are locked on as Blocking.
 */
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleSettingsController {
    /** Unknown or lookalike vendor, bank-detail fraud, exact duplicates, already paid by card. */
    public static final Set<String> LOCKED = Set.of("V01", "V04", "B01", "B02", "D01", "D03", "D04");

    private final FlagDictionaryRepository dictionary;
    private final AuditService audit;
    private final RevalidationService revalidation;
    private final TransactionTemplate tx;

    public record RuleSetting(String code, String category, String title, String explanation, Severity severity,
                              Severity defaultSeverity, boolean enabled, boolean locked) {}

    public record RuleUpdate(Boolean enabled, Severity severity, String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('FM','ADM','AUD')")
    public List<RuleSetting> list() {
        Map<String, FlagDictionary> saved = new HashMap<>();
        dictionary.findAll().forEach(d -> saved.put(d.getCode(), d));
        List<RuleSetting> out = new ArrayList<>();
        // Email intake codes (E01-E04) describe how an email was sorted; they are not invoice checks.
        for (FlagDictionary def : FlagDictionarySeed.ENTRIES) {
            if (def.getCode().startsWith("E0")) continue;
            FlagDictionary cur = saved.getOrDefault(def.getCode(), def);
            out.add(new RuleSetting(def.getCode(), def.getCategory(), def.getTitle(), def.getExplanation(),
                    cur.getSeverity() == null ? def.getSeverity() : cur.getSeverity(), def.getSeverity(), cur.isOn(), LOCKED.contains(def.getCode())));
        }
        return out;
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADM')")
    public RuleSetting update(@PathVariable String code, @RequestBody RuleUpdate u) {
        if (u.reason() == null || u.reason().isBlank()) throw new IllegalArgumentException("Give a reason for changing a validation rule");
        if (code.startsWith("E0")) throw new NotFoundException("Validation rule " + code);
        boolean locked = LOCKED.contains(code);
        if (locked && (Boolean.FALSE.equals(u.enabled()) || (u.severity() != null && u.severity() != Severity.BLOCK))) {
            throw new BusinessRuleException("This is a core fraud or duplicate control: it always runs and always blocks payment");
        }
        tx.executeWithoutResult(s -> {
            FlagDictionary d = dictionary.findById(code).orElseThrow(() -> new NotFoundException("Validation rule " + code));
            Map<String, Object> before = Map.of("enabled", d.isOn(), "severity", String.valueOf(d.getSeverity()));
            if (u.enabled() != null) d.setEnabled(u.enabled());
            if (u.severity() != null) d.setSeverity(u.severity());
            Map<String, Object> after = Map.of("enabled", d.isOn(), "severity", String.valueOf(d.getSeverity()));
            if (before.equals(after)) throw new BusinessRuleException("Nothing changed");
            dictionary.save(d);
            audit.record("RULE_CHANGED", "rule", code, before, after, d.getTitle() + ": "
                    + (d.isOn() ? "on, " + d.getSeverity().name().toLowerCase(Locale.ROOT) : "switched off") + ". Reason: " + u.reason().trim());
        });
        revalidation.revalidateOpen(); // open bills pick the change up now, not at their next edit
        return list().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }
}
