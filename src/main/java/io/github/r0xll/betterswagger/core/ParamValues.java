package io.github.r0xll.betterswagger.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-supplied parameter values, parsed from the tab's text area. One per line:
 *
 * <pre>
 *   petId=1337                 # applies to every parameter named petId
 *   /users/{id} id=42          # applies only to endpoints with this path template
 *   # comment
 * </pre>
 *
 * A path-scoped value wins over a global one, so a generic name such as
 * {@code id} can carry a different real value for each resource. Later lines win
 * over earlier ones, so appending (e.g. an import) overrides.
 */
public final class ParamValues {

    private final Map<String, String> global;
    private final Map<String, Map<String, String>> scoped;

    private ParamValues(Map<String, String> global, Map<String, Map<String, String>> scoped) {
        this.global = global;
        this.scoped = scoped;
    }

    public static ParamValues parse(String text) {
        Map<String, String> global = new LinkedHashMap<>();
        Map<String, Map<String, String>> scoped = new LinkedHashMap<>();
        for (String raw : (text == null ? "" : text).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String path = null;
            if (line.startsWith("/")) {
                int space = firstWhitespace(line);
                if (space < 0) {
                    continue;
                }
                path = line.substring(0, space);
                line = line.substring(space + 1).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (path == null) {
                global.put(name, value);
            } else {
                scoped.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(name, value);
            }
        }
        return new ParamValues(global, scoped);
    }

    /** The endpoint with these values applied (path-scoped over global). */
    public Endpoint applyTo(Endpoint endpoint) {
        Map<String, String> forPath = scoped.get(endpoint.path());
        if (forPath == null) {
            return endpoint.withParamValues(global);
        }
        Map<String, String> merged = new LinkedHashMap<>(global);
        merged.putAll(forPath);
        return endpoint.withParamValues(merged);
    }

    public List<Endpoint> applyTo(List<Endpoint> endpoints) {
        List<Endpoint> out = new ArrayList<>(endpoints.size());
        for (Endpoint e : endpoints) {
            out.add(applyTo(e));
        }
        return out;
    }

    /** True when a value is already set for {@code name} on {@code path} (scoped or global). */
    public boolean covers(String path, String name) {
        return global.containsKey(name) || scoped.getOrDefault(path, Map.of()).containsKey(name);
    }

    public Map<String, String> global() {
        return Collections.unmodifiableMap(global);
    }

    public static String line(String path, String name, String value) {
        return path == null ? name + "=" + value : path + " " + name + "=" + value;
    }

    /**
     * Lines for path parameters that have no value yet, pre-filled with the current
     * placeholder: a global line when the name is used on one path only, otherwise
     * one path-scoped line per path, so a shared name like {@code id} can differ.
     */
    public List<String> missingPathParameterLines(List<Endpoint> endpoints) {
        Map<String, Set<String>> pathsByName = new LinkedHashMap<>();
        Map<String, String> placeholder = new LinkedHashMap<>();
        for (Endpoint e : endpoints) {
            for (Param p : e.parameters()) {
                if (p.in() == Param.Location.PATH) {
                    pathsByName.computeIfAbsent(p.name(), n -> new LinkedHashSet<>()).add(e.path());
                    placeholder.putIfAbsent(e.path() + "\n" + p.name(), p.exampleValue());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        pathsByName.forEach((name, paths) -> {
            boolean shared = paths.size() > 1;
            for (String path : paths) {
                if (!covers(path, name)) {
                    lines.add(line(shared ? path : null, name, placeholder.get(path + "\n" + name)));
                }
                if (!shared) {
                    break;
                }
            }
        });
        return lines;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
