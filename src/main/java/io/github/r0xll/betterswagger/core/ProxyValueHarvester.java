package io.github.r0xll.betterswagger.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects real parameter values from observed request paths (e.g. Burp's Proxy
 * history): {@code /v1/pets/1337} against the template {@code /pets/{petId}} under
 * base path {@code /v1} yields {@code petId=1337}, and declared query parameters
 * of a matched endpoint are captured by name. The most recently observed value
 * wins.
 *
 * <p>It only ever receives strings, so the caller keeps no reference to Burp's
 * message objects (BApp rule 7). Templates are compiled once with their literal
 * parts quoted, and indexed by segment count and first segment, so matching a
 * large history stays cheap and an untrusted template cannot inject a regex.
 * When several templates match, only the most specific (fewest placeholders)
 * captures, so {@code /pets/mine} is not taken as a {@code petId}.
 */
public final class ProxyValueHarvester {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}/]{1,200})\\}");
    private static final int MAX_VALUE_LENGTH = 1024;

    private record Template(Endpoint endpoint, Pattern pattern, List<String> names, int placeholders,
                            List<String> queryNames) { }

    /** segment count -> first literal segment ("" when it is a placeholder) -> templates */
    private final Map<Integer, Map<String, List<Template>>> index = new HashMap<>();
    private final Map<String, Map<String, String>> values = new LinkedHashMap<>();
    private int matched;

    public ProxyValueHarvester(List<Endpoint> endpoints, String basePath) {
        String prefix = basePath == null ? "" : stripTrailingSlash(basePath);
        for (Endpoint e : endpoints) {
            String full = prefix + (e.path().startsWith("/") ? e.path() : "/" + e.path());
            List<String> names = new ArrayList<>();
            StringBuilder regex = new StringBuilder();
            Matcher m = PLACEHOLDER.matcher(full);
            int last = 0;
            while (m.find()) {
                regex.append(Pattern.quote(full.substring(last, m.start()))).append("([^/]+)");
                names.add(m.group(1));
                last = m.end();
            }
            regex.append(Pattern.quote(full.substring(last)));
            List<String> queryNames = new ArrayList<>();
            for (Param p : e.parameters()) {
                if (p.in() == Param.Location.QUERY) {
                    queryNames.add(p.name());
                }
            }
            // Literal-only templates are indexed too: they capture nothing, but they
            // stop a looser template (/pets/{petId}) from taking "mine" as an ID.
            String[] segments = segments(full);
            String first = segments.length > 0 && !PLACEHOLDER.matcher(segments[0]).find() ? segments[0] : "";
            index.computeIfAbsent(segments.length, k -> new HashMap<>())
                 .computeIfAbsent(first, k -> new ArrayList<>())
                 .add(new Template(e, Pattern.compile(regex.toString()), names, names.size(), queryNames));
        }
    }

    /** Feed one observed request target (path plus optional query string). */
    public void observe(String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            return;
        }
        int q = pathAndQuery.indexOf('?');
        String path = q >= 0 ? pathAndQuery.substring(0, q) : pathAndQuery;
        String query = q >= 0 ? pathAndQuery.substring(q + 1) : "";
        String[] segments = segments(path);
        Map<String, List<Template>> bySegment = index.get(segments.length);
        if (bySegment == null) {
            return;
        }

        List<Template> candidates = new ArrayList<>(bySegment.getOrDefault("", List.of()));
        if (segments.length > 0) {
            candidates.addAll(bySegment.getOrDefault(segments[0], List.of()));
        }
        List<Matcher> hits = new ArrayList<>();
        List<Template> hitTemplates = new ArrayList<>();
        int best = Integer.MAX_VALUE;
        for (Template t : candidates) {
            Matcher m = t.pattern.matcher(path);
            if (m.matches()) {
                if (t.placeholders < best) {
                    best = t.placeholders;
                    hits.clear();
                    hitTemplates.clear();
                }
                if (t.placeholders == best) {
                    hits.add(m);
                    hitTemplates.add(t);
                }
            }
        }
        if (hits.isEmpty()) {
            return;
        }
        matched++;
        Map<String, String> observedQuery = parseQuery(query);
        for (int i = 0; i < hits.size(); i++) {
            Template t = hitTemplates.get(i);
            String template = t.endpoint.path();
            for (int g = 0; g < t.names.size(); g++) {
                put(template, t.names.get(g), decode(hits.get(i).group(g + 1)));
            }
            for (String name : t.queryNames) {
                if (observedQuery.containsKey(name)) {
                    put(template, name, observedQuery.get(name));
                }
            }
        }
    }

    /** Values found, by endpoint path template then parameter name. */
    public Map<String, Map<String, String>> values() {
        return values;
    }

    /** How many observed requests matched an endpoint. */
    public int matched() {
        return matched;
    }

    private void put(String path, String name, String value) {
        if (!value.isEmpty() && value.length() <= MAX_VALUE_LENGTH && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            values.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(name, value);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String[] segments(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/", -1);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
