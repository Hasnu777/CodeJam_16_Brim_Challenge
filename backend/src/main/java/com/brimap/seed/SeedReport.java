package com.brimap.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record SeedReport(Map<String, Integer> loaded, Map<String, Integer> expected, long millis) {
    public List<String> mismatches() {
        List<String> out = new ArrayList<>();
        expected.forEach((k, v) -> {
            Integer got = loaded.get(k);
            if (!v.equals(got)) out.add(k + ": expected " + v + ", loaded " + got);
        });
        return out;
    }

    public boolean allMatch() { return mismatches().isEmpty(); }
}
