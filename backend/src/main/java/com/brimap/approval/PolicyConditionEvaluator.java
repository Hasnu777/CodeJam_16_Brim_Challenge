package com.brimap.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates a policy condition tree against a map of facts. Grammar:
 * <pre>
 *   {"field": "amountCad", "op": "gt", "value": 10000}
 *   {"all": [cond, ...]}   {"any": [cond, ...]}   {"not": cond}   {"always": true}
 * </pre>
 * Ops: eq, ne, gt, gte, lt, lte, in (value is an array). Unknown fields are null (so "eq" is false, "ne" true).
 * Pure; unit-tested; {@link #validate} is used before an Admin's edit is saved.
 */
public final class PolicyConditionEvaluator {
    private PolicyConditionEvaluator() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static boolean evaluate(String conditionJson, Map<String, Object> facts) {
        try {
            return eval(JSON.readTree(conditionJson), facts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid condition JSON: " + e.getOriginalMessage());
        }
    }

    static boolean eval(JsonNode n, Map<String, Object> facts) {
        if (n.has("always")) return n.get("always").asBoolean();
        if (n.has("all")) {
            for (JsonNode c : n.get("all")) if (!eval(c, facts)) return false;
            return true;
        }
        if (n.has("any")) {
            for (JsonNode c : n.get("any")) if (eval(c, facts)) return true;
            return false;
        }
        if (n.has("not")) return !eval(n.get("not"), facts);
        String field = n.path("field").asText();
        String op = n.path("op").asText();
        JsonNode value = n.get("value");
        Object actual = facts.get(field);
        return switch (op) {
            case "eq" -> equalsValue(actual, value);
            case "ne" -> !equalsValue(actual, value);
            case "gt" -> cmp(actual, value) != null && cmp(actual, value) > 0;
            case "gte" -> cmp(actual, value) != null && cmp(actual, value) >= 0;
            case "lt" -> cmp(actual, value) != null && cmp(actual, value) < 0;
            case "lte" -> cmp(actual, value) != null && cmp(actual, value) <= 0;
            case "in" -> {
                if (value == null || !value.isArray()) yield false;
                for (JsonNode v : value) if (equalsValue(actual, v)) yield true;
                yield false;
            }
            default -> throw new IllegalArgumentException("Unknown op " + op);
        };
    }

    private static boolean equalsValue(Object actual, JsonNode v) {
        if (actual == null || v == null || v.isNull()) return actual == null && (v == null || v.isNull());
        if (actual instanceof Boolean b) return v.isBoolean() && b == v.asBoolean();
        if (actual instanceof Number num) return v.isNumber() && new BigDecimal(num.toString()).compareTo(v.decimalValue()) == 0;
        return Objects.equals(actual.toString(), v.asText());
    }

    /** Numeric comparison, or null when the fact is missing / not numeric (then every numeric op is false). */
    private static Integer cmp(Object actual, JsonNode v) {
        if (actual == null || v == null || !v.isNumber()) return null;
        try {
            BigDecimal a = actual instanceof BigDecimal bd ? bd : new BigDecimal(actual.toString());
            return a.compareTo(v.decimalValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Throws IllegalArgumentException with a readable reason if the tree is malformed. */
    public static void validate(String conditionJson) {
        JsonNode n;
        try {
            n = JSON.readTree(conditionJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Condition is not valid JSON");
        }
        check(n);
    }

    private static void check(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("Each condition must be an object");
        if (n.has("always")) return;
        if (n.has("all") || n.has("any")) {
            JsonNode arr = n.has("all") ? n.get("all") : n.get("any");
            if (!arr.isArray() || arr.isEmpty()) throw new IllegalArgumentException("all/any needs a non-empty array");
            arr.forEach(PolicyConditionEvaluator::check);
            return;
        }
        if (n.has("not")) { check(n.get("not")); return; }
        if (!n.hasNonNull("field") || !n.hasNonNull("op")) throw new IllegalArgumentException("A comparison needs field and op");
        String op = n.get("op").asText();
        if (!java.util.Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in").contains(op)) throw new IllegalArgumentException("Unknown op " + op);
        if (java.util.Set.of("gt", "gte", "lt", "lte").contains(op) && (n.get("value") == null || !n.get("value").isNumber())) {
            throw new IllegalArgumentException(op + " needs a numeric value");
        }
    }
}
