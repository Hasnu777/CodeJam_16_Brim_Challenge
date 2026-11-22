package com.brimap.admin;

import com.brimap.model.Department;
import com.brimap.model.FlagDictionary;
import com.brimap.model.GlAccount;
import com.brimap.model.TaxCode;
import com.brimap.repository.DepartmentRepository;
import com.brimap.repository.FlagDictionaryRepository;
import com.brimap.repository.GlAccountRepository;
import com.brimap.repository.TaxCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Dropdown reference data. Any logged-in role. */
@RestController
@RequestMapping("/api/ref")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RefController {
    private final GlAccountRepository gl;
    private final TaxCodeRepository tax;
    private final DepartmentRepository depts;
    private final FlagDictionaryRepository flags;

    @GetMapping("/gl-accounts")
    public List<GlAccount> gl() { return gl.findAll().stream().sorted(Comparator.comparing(GlAccount::getAccountNumber)).toList(); }

    @GetMapping("/tax-codes")
    public List<TaxCode> tax() { return tax.findAll(); }

    @GetMapping("/departments")
    public List<Department> departments() { return depts.findAll().stream().sorted(Comparator.comparing(Department::getId)).toList(); }

    @GetMapping("/flag-dictionary")
    public List<FlagDictionary> flagDictionary() { return flags.findAll(); }
}
