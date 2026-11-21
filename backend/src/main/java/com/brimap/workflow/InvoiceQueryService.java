package com.brimap.workflow;

import com.brimap.auth.CurrentUser;
import com.brimap.model.*;
import com.brimap.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Read side for invoices: scoped list + full detail. */
@Service
@RequiredArgsConstructor
public class InvoiceQueryService {
    private final EntityManager em;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceTaxRepository taxes;
    private final FlagRepository flags;
    private final ApprovalStepRepository steps;
    private final VendorRepository vendors;
    private final AppUserRepository users;
    private final PaymentRepository payments;
    private final ExtractionRepository extractions;
    private final AuditEventRepository auditEvents;
    private final InvoiceAccess access;

    public record InvoiceSummary(String id, String vendorId, String vendorName, String vendorInvoiceNumber, LocalDate invoiceDate,
                                 LocalDate dueDate, String currency, BigDecimal total, BigDecimal totalCad, String departmentId,
                                 String requesterUserId, InvoiceStatus status, boolean historical, String source, boolean creditNote,
                                 long blockFlags, long warnFlags, long infoFlags, String currentApproverId, String currentApproverName,
                                 String topFlag) {}

    @Transactional(readOnly = true)
    public List<InvoiceSummary> list(CurrentUser u, String status, String dept, Boolean flagged, String q, String vendorId,
                                     Boolean includeHistory, int limit) {
        StringBuilder jpql = new StringBuilder("select i from Invoice i where 1=1");
        Map<String, Object> p = new HashMap<>();
        if (status != null && !status.isBlank()) {
            List<InvoiceStatus> sts = Arrays.stream(status.split(",")).map(String::trim).map(InvoiceStatus::valueOf).toList();
            jpql.append(" and i.status in :st"); p.put("st", sts);
        }
        if (dept != null && !dept.isBlank()) { jpql.append(" and i.departmentId = :dept"); p.put("dept", dept); }
        if (vendorId != null && !vendorId.isBlank()) { jpql.append(" and i.vendorId = :vendor"); p.put("vendor", vendorId); }
        if (!Boolean.TRUE.equals(includeHistory)) jpql.append(" and i.historical = false");
        // Scope is pushed into the query for EMP; APR is filtered after (assignment can cross departments).
        if (u.role() == Role.EMP) { jpql.append(" and i.requesterUserId = :me"); p.put("me", u.id()); }
        jpql.append(" order by i.createdAt desc, i.id desc");
        var query = em.createQuery(jpql.toString(), Invoice.class);
        p.forEach(query::setParameter);
        List<Invoice> rows = query.getResultList();

        Map<String, String> vendorNames = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        Map<String, String> userNames = users.findAll().stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        Set<String> ids = rows.stream().map(Invoice::getId).collect(Collectors.toSet());
        Map<String, List<Flag>> flagsBy = ids.isEmpty() ? Map.of() : em.createQuery(
                        "select f from Flag f where f.invoiceId in :ids and f.status in ('OPEN','ACKNOWLEDGED')", Flag.class)
                .setParameter("ids", ids).getResultList().stream().collect(Collectors.groupingBy(Flag::getInvoiceId));
        Map<String, ApprovalStep> pending = ids.isEmpty() ? Map.of() : em.createQuery(
                        "select s from ApprovalStep s where s.invoiceId in :ids and s.decision = 'PENDING' order by s.seq", ApprovalStep.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.toMap(ApprovalStep::getInvoiceId, s -> s, (a, b) -> a.getSeq() <= b.getSeq() ? a : b));

        String needle = q == null ? null : q.toLowerCase(Locale.ROOT).trim();
        List<InvoiceSummary> out = new ArrayList<>();
        for (Invoice i : rows) {
            if (u.role() == Role.APR && !access.canView(u, i)) continue;
            List<Flag> fl = flagsBy.getOrDefault(i.getId(), List.of());
            if (Boolean.TRUE.equals(flagged) && fl.isEmpty()) continue;
            String vName = i.getVendorId() != null ? vendorNames.get(i.getVendorId()) : i.getVendorNameRaw();
            if (needle != null && !needle.isEmpty()) {
                String hay = (i.getId() + " " + vName + " " + i.getVendorInvoiceNumber() + " " + i.getVendorNameRaw()).toLowerCase(Locale.ROOT);
                if (!hay.contains(needle)) continue;
            }
            ApprovalStep cur = pending.get(i.getId());
            Flag top = fl.stream().min(Comparator.comparing(Flag::getSeverity)).orElse(null);
            out.add(new InvoiceSummary(i.getId(), i.getVendorId(), vName, i.getVendorInvoiceNumber(), i.getInvoiceDate(), i.getDueDate(),
                    i.getCurrency(), i.getTotal(), i.getTotalCad(), i.getDepartmentId(), i.getRequesterUserId(), i.getStatus(),
                    i.isHistorical(), i.getSource(), i.isCreditNote(),
                    fl.stream().filter(f -> f.getSeverity() == Severity.BLOCK).count(),
                    fl.stream().filter(f -> f.getSeverity() == Severity.WARN).count(),
                    fl.stream().filter(f -> f.getSeverity() == Severity.INFO).count(),
                    cur == null ? null : cur.getAssignedUserId(), cur == null ? null : userNames.get(cur.getAssignedUserId()),
                    top == null ? null : top.getRuleCode() + ": " + top.getTitle()));
            if (out.size() >= limit) break;
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(CurrentUser u, String id) {
        Invoice i = invoices.findById(id).orElseThrow(() -> new com.brimap.common.NotFoundException("Invoice " + id));
        access.requireView(u, i);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoice", i);
        m.put("vendor", i.getVendorId() == null ? null : vendors.findById(i.getVendorId()).orElse(null));
        m.put("lines", lines.findByInvoiceIdOrderByLineNo(id));
        m.put("taxes", taxes.findByInvoiceId(id));
        List<Flag> fl = new ArrayList<>(flags.findByInvoiceId(id));
        fl.sort(Comparator.comparing(Flag::getSeverity).thenComparing(Flag::getRuleCode));
        m.put("flags", fl);
        m.put("approvalSteps", steps.findByInvoiceIdOrderBySeq(id));
        m.put("payments", payments.findByInvoiceId(id));
        m.put("extractions", extractions.findByInvoiceIdOrderByCreatedAtDesc(id).stream().limit(1).toList());
        m.put("history", auditEvents.findByEntityAndEntityIdOrderByIdAsc("invoice", id));
        m.put("users", users.findAll().stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName)));
        m.put("allowedNext", InvoiceStateMachine.next(i.getStatus()));
        // Bonus 3: approval recommendation from facts already computed
        long paid = i.getVendorId() == null ? 0 : invoices.findByVendorId(i.getVendorId()).stream()
                .filter(x -> x.getStatus() == InvoiceStatus.PAID && !x.getId().equals(id)).count();
        Vendor v = (Vendor) m.get("vendor");
        m.put("recommendation", com.brimap.bonus.Recommendation.of(i, fl, paid, v != null && "active".equals(v.getStatus())));
        return m;
    }
}
