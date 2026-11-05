package parity.cctools.health;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import parity.cctools.catalogue.Catalogue;

/**
 * The few series the catalogue lists, from Prometheus's text format (PLAN.md §6.3): a series counts when
 * its name matches and every filter label has the given value; matching series are summed. Anything else
 * in the text is skipped; the caller caps the input (4 MiB).
 */
public final class PrometheusText {

    private PrometheusText() {}

    /** Metric title (or name) to the sum of its matching series; a listed metric with no series is absent. */
    public static Map<String, Double> pick(String text, List<Catalogue.MetricDef> wanted) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (text == null || wanted.isEmpty()) {
            return out;
        }
        Map<String, List<Catalogue.MetricDef>> byName = new HashMap<>();
        for (Catalogue.MetricDef m : wanted) {
            byName.computeIfAbsent(m.name(), k -> new java.util.ArrayList<>()).add(m);
        }
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int brace = line.indexOf('{');
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String name = brace >= 0 && brace < space ? line.substring(0, brace) : line.substring(0, space);
            List<Catalogue.MetricDef> defs = byName.get(name);
            if (defs == null) {
                continue;
            }
            Map<String, String> labels = Map.of();
            String rest;
            if (brace >= 0 && brace < space) {
                int close = line.indexOf('}', brace);
                if (close < 0) {
                    continue;
                }
                labels = labels(line.substring(brace + 1, close));
                rest = line.substring(close + 1).strip();
            } else {
                rest = line.substring(space + 1).strip();
            }
            String[] parts = rest.split("\\s+", 2);
            double value;
            try {
                value = Double.parseDouble(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            for (Catalogue.MetricDef d : defs) {
                if (matches(labels, d.labels())) {
                    out.merge(d.title() == null ? d.name() : d.title(), value, Double::sum);
                }
            }
        }
        return out;
    }

    static boolean matches(Map<String, String> have, Map<String, String> want) {
        for (var e : want.entrySet()) {
            if (!e.getValue().equals(have.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** {@code a="1",b="x\"y"} to a map (escapes undone). */
    static Map<String, String> labels(String s) {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        while (i < s.length()) {
            int eq = s.indexOf('=', i);
            if (eq < 0 || eq + 1 >= s.length() || s.charAt(eq + 1) != '"') {
                break;
            }
            String key = s.substring(i, eq).strip();
            StringBuilder v = new StringBuilder();
            int j = eq + 2;
            while (j < s.length() && s.charAt(j) != '"') {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < s.length()) {
                    char n = s.charAt(++j);
                    v.append(n == 'n' ? '\n' : n);
                } else {
                    v.append(c);
                }
                j++;
            }
            out.put(key, v.toString());
            i = j + 1;
            while (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == ' ')) {
                i++;
            }
        }
        return out;
    }
}
