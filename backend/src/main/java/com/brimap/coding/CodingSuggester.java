package com.brimap.coding;

import com.brimap.model.InvoiceLine;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GL account + department suggestion from history. Pure function, unit-tested.
 * Order: (1) how this vendor's past lines were coded (frequency); (2) lines from any vendor with similar
 * descriptions (token overlap); (3) the vendor's default GL account. The reason always shows the counts.
 */
public final class CodingSuggester {
    private CodingSuggester() {}

    public record Suggestion(String glAccount, String departmentId, String reason, String source) {}

    public record HistoryLine(String invoiceId, String vendorId, String description, String glAccount, String departmentId) {}

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "des", "les", "pour", "inc", "ltd", "per", "month", "monthly");
    public static final double MIN_SIMILARITY = 0.3;

    public static Suggestion suggest(InvoiceLine line, String vendorId, String vendorName, String vendorDefaultGl, String invoiceDepartment,
                                     List<HistoryLine> history, Map<String, String> glNames) {
        Function<String, String> gl = g -> g + (glNames.containsKey(g) ? " " + glNames.get(g) : "");
        List<HistoryLine> mine = vendorId == null ? List.of() : history.stream().filter(h -> vendorId.equals(h.vendorId())).toList();
        if (!mine.isEmpty()) {
            Map.Entry<String, Long> top = mode(mine, HistoryLine::glAccount);
            String dept = invoiceDepartment != null ? invoiceDepartment : mode(mine, HistoryLine::departmentId).getKey();
            long invoicesSeen = mine.stream().map(HistoryLine::invoiceId).distinct().count();
            String reason = top.getValue() + " of " + mine.size() + " past invoice lines from " + vendorName + " (" + invoicesSeen
                    + " invoices) went to " + gl.apply(top.getKey());
            if (invoiceDepartment == null) {
                Map.Entry<String, Long> d = mode(mine, HistoryLine::departmentId);
                reason += "; " + d.getValue() + " of " + mine.size() + " were charged to " + d.getKey();
            }
            return new Suggestion(top.getKey(), dept, reason, "VENDOR_HISTORY");
        }
        Set<String> tokens = tokens(line.getDescription());
        if (!tokens.isEmpty()) {
            record Scored(HistoryLine h, double s) {}
            List<Scored> similar = history.stream().map(h -> new Scored(h, jaccard(tokens, tokens(h.description()))))
                    .filter(s -> s.s() >= MIN_SIMILARITY).sorted(Comparator.comparingDouble(Scored::s).reversed()).limit(10).toList();
            if (!similar.isEmpty()) {
                Map<String, Double> votes = new HashMap<>();
                similar.forEach(s -> votes.merge(s.h().glAccount(), s.s(), Double::sum));
                String best = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                Scored example = similar.stream().filter(s -> s.h().glAccount().equals(best)).findFirst().get();
                long n = similar.stream().filter(s -> s.h().glAccount().equals(best)).count();
                String dept = invoiceDepartment != null ? invoiceDepartment : example.h().departmentId();
                return new Suggestion(best, dept, "No history with this vendor; " + n + " of " + similar.size() + " similar past lines went to "
                        + gl.apply(best) + " (e.g. \"" + example.h().description() + "\" on " + example.h().invoiceId() + ")", "SIMILAR_LINES");
            }
        }
        if (vendorDefaultGl != null) {
            return new Suggestion(vendorDefaultGl, invoiceDepartment, "No history; vendor's default account is " + gl.apply(vendorDefaultGl), "VENDOR_DEFAULT");
        }
        return new Suggestion(null, invoiceDepartment, "No history, no similar lines and no vendor default: please code manually", "NONE");
    }

    static Set<String> tokens(String s) {
        if (s == null) return Set.of();
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() >= 3 && !STOP.contains(t) && !t.matches("\\d+")).collect(Collectors.toSet());
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static Map.Entry<String, Long> mode(List<HistoryLine> rows, Function<HistoryLine, String> f) {
        return rows.stream().map(f).filter(Objects::nonNull).collect(Collectors.groupingBy(x -> x, Collectors.counting()))
                .entrySet().stream()
                // highest count wins; ties go to the lowest account/department code so results are deterministic
                .sorted((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()))
                .findFirst().orElse(Map.entry("", 0L));
    }
}
