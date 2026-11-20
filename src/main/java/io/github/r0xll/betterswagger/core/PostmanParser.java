package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Postman Collection (v2.0 / v2.1 JSON), optionally with the variables of
 * a Postman environment export, into the same {@link ApiSpec} model the OpenAPI
 * parser produces, so sending, parameter values and the OpenAPI export work on
 * collections unchanged.
 *
 * <p>Collections are treated as untrusted (BApp rule 1): pre-request and test
 * scripts are never executed, only counted and reported; variable expansion is
 * bounded in depth and size; folder nesting and request count are capped; and
 * methods that are not plain tokens are rejected. Variable <em>values</em> may be
 * secrets, so warnings name variables but never include their values.
 */
public final class PostmanParser {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_REQUESTS = 10_000;
    private static final int MAX_VAR_PASSES = 5;
    private static final int MAX_EXPANDED = 64 * 1024;
    private static final int MAX_LISTED_NAMES = 20;
    private static final Pattern VAR = Pattern.compile("\\{\\{([^{}]{1,200})\\}\\}");
    private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^{}/]{1,200})\\}");
    private static final Pattern METHOD = Pattern.compile("[A-Za-z]{1,20}");
    static final String FORM_BOUNDARY = "----BetterSwaggerParserFormBoundary7MA4YWxkTrZu0gW";

    private final ObjectMapper mapper = new ObjectMapper();

    /** True for a Postman collection export (v2.0 or v2.1). */
    public static boolean isCollection(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("info").isObject()) {
            return false;
        }
        JsonNode info = root.get("info");
        return info.path("schema").asText("").contains("schema.getpostman.com")
                || (info.has("_postman_id") && root.has("item"));
    }

    /** True for a Postman environment (or globals) export. */
    public static boolean isEnvironment(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("values").isArray()) {
            return false;
        }
        String scope = root.path("_postman_variable_scope").asText("");
        if ("environment".equals(scope) || "globals".equals(scope)) {
            return true;
        }
        return root.has("name") && !root.has("info") && !root.has("item")
                && !root.has("openapi") && !root.has("swagger");
    }

    /** The enabled variables of an environment export, by name. */
    public static Map<String, String> readEnvironment(JsonNode root) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (JsonNode v : root.path("values")) {
            if (!v.isObject() || !v.path("enabled").asBoolean(true)) {
                continue;
            }
            String key = v.path("key").asText("");
            if (!key.isEmpty()) {
                vars.put(key, scalar(v.get("value")));
            }
        }
        return vars;
    }

    public static String environmentName(JsonNode root) {
        return root.path("name").asText("environment");
    }

    /**
     * @param environment variables from an environment export; they override the
     *                    collection's own variables, as in Postman. May be empty.
     */
    public ParseResult parse(JsonNode root, Map<String, String> environment) {
        if (!isCollection(root)) {
            throw new SpecException("Not a Postman collection.");
        }
        Context ctx = new Context(environment == null ? Map.of() : environment, collectionVariables(root));
        ctx.countScripts(root);
        walk(root.get("item"), "", root.get("auth"), 0, ctx);

        if (ctx.scripts > 0) {
            ctx.warnings.add("Ignored " + ctx.scripts + " pre-request/test script(s): scripts are never "
                    + "executed. Supply values they would set at run time (e.g. tokens) as parameter "
                    + "values, custom headers or an auth profile.");
        }
        if (!ctx.unresolved.isEmpty()) {
            ctx.warnings.add("Undefined variables, left as {{name}}: " + listNames(ctx.unresolved)
                    + ". Load a Postman environment or set them as parameter values.");
        }
        if (ctx.origins.size() > 1) {
            ctx.warnings.add("The collection targets " + ctx.origins.size() + " hosts; requests are sent "
                    + "to the base URL selected above.");
        }
        List<String> baseUrls = new ArrayList<>(ctx.origins.keySet());
        baseUrls.sort((a, b) -> Integer.compare(ctx.origins.get(b), ctx.origins.get(a)));
        String title = root.path("info").path("name").asText("Postman collection");
        return new ParseResult(new ApiSpec(ApiSpec.Version.POSTMAN_2, title, baseUrls, ctx.endpoints),
                ctx.warnings);
    }

    // ---- Traversal ---------------------------------------------------------

    private void walk(JsonNode items, String prefix, JsonNode inheritedAuth, int depth, Context ctx) {
        if (items == null || !items.isArray()) {
            return;
        }
        if (depth > MAX_DEPTH) {
            ctx.warnOnce("Folders nested deeper than " + MAX_DEPTH + " levels were skipped.");
            return;
        }
        for (JsonNode item : items) {
            if (ctx.endpoints.size() >= MAX_REQUESTS) {
                ctx.warnOnce("Only the first " + MAX_REQUESTS + " requests were imported.");
                return;
            }
            if (!item.isObject()) {
                continue;
            }
            ctx.countScripts(item);
            String name = item.path("name").asText("");
            String label = prefix.isEmpty() ? name : prefix + " / " + name;
            JsonNode auth = effectiveAuth(item.get("auth"), inheritedAuth);
            if (item.has("item")) {
                walk(item.get("item"), label, auth, depth + 1, ctx);
            } else if (item.has("request")) {
                addRequest(item.get("request"), label, auth, ctx);
            }
        }
    }

    /** An item's own auth wins unless absent or explicitly "inherit". */
    private static JsonNode effectiveAuth(JsonNode own, JsonNode inherited) {
        if (own == null || !own.isObject() || "inherit".equals(own.path("type").asText(""))) {
            return inherited;
        }
        return own;
    }

    private void addRequest(JsonNode request, String label, JsonNode inheritedAuth, Context ctx) {
        JsonNode urlNode;
        String method;
        if (request.isTextual()) { // v2.0 allows "request": "<url>"
            urlNode = request;
            method = "GET";
        } else if (request.isObject()) {
            urlNode = request.get("url");
            method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        } else {
            return;
        }
        if (!METHOD.matcher(method).matches()) {
            ctx.warnOnce("Skipped request(s) with an invalid HTTP method.");
            return;
        }

        Url url = parseUrl(urlNode, ctx);
        if (url == null) {
            ctx.warnOnce("Skipped request(s) whose URL is not http(s).");
            return;
        }
        if (url.origin != null) {
            ctx.origins.merge(url.origin, 1, Integer::sum);
        }

        List<Param> params = new ArrayList<>();
        Set<String> seenPath = new LinkedHashSet<>();
        Matcher m = TEMPLATE_PARAM.matcher(url.pathTemplate);
        while (m.find()) {
            String name = m.group(1);
            if (seenPath.add(name)) {
                String value = url.pathValues.containsKey(name) ? url.pathValues.get(name)
                        : (url.unresolvedPathVars.contains(name) ? "{{" + name + "}}" : "");
                params.add(new Param(name, Param.Location.PATH, true, value));
            }
        }
        params.addAll(url.query);

        String headerContentType = null;
        JsonNode body = request.isObject() ? request.get("body") : null;
        String bodyMode = body != null && body.isObject() ? body.path("mode").asText("") : "";
        for (String[] header : headers(request.isObject() ? request.get("header") : null, ctx)) {
            if ("content-type".equalsIgnoreCase(header[0])) {
                if ("formdata".equals(bodyMode)) {
                    continue; // multipart needs our own boundary
                }
                headerContentType = header[1];
            }
            params.add(new Param(header[0], Param.Location.HEADER, false, header[1]));
        }

        String[] built = body(body, bodyMode, headerContentType, ctx);
        JsonNode auth = request.isObject() ? effectiveAuth(request.get("auth"), inheritedAuth) : inheritedAuth;
        ctx.endpoints.add(new Endpoint(method, url.pathTemplate, label, params, auth(auth, ctx),
                built == null ? null : built[0], built == null ? null : built[1]));
    }

    // ---- URL ---------------------------------------------------------------

    private static final class Url {
        String origin;
        String pathTemplate;
        final Map<String, String> pathValues = new LinkedHashMap<>();
        final Set<String> unresolvedPathVars = new LinkedHashSet<>();
        final List<Param> query = new ArrayList<>();
    }

    private Url parseUrl(JsonNode urlNode, Context ctx) {
        String raw;
        JsonNode queryEntries = null;
        Url url = new Url();
        if (urlNode == null) {
            raw = "";
        } else if (urlNode.isTextual()) {
            raw = urlNode.asText();
        } else if (urlNode.isObject()) {
            raw = urlNode.path("raw").asText("");
            if (raw.isEmpty()) {
                raw = fromParts(urlNode);
            }
            if (urlNode.path("query").isArray()) {
                queryEntries = urlNode.get("query");
            }
            for (JsonNode v : urlNode.path("variable")) {
                String key = v.path("key").asText("");
                if (!key.isEmpty()) {
                    url.pathValues.put(key, ctx.resolve(scalar(v.get("value"))));
                }
            }
        } else {
            return null;
        }

        int hash = raw.indexOf('#');
        if (hash >= 0) {
            raw = raw.substring(0, hash);
        }
        int q = raw.indexOf('?');
        String base = q >= 0 ? raw.substring(0, q) : raw;
        String queryString = q >= 0 ? raw.substring(q + 1) : "";

        base = ctx.resolve(base.trim());
        // A host that is still an undefined variable ({{baseUrl}}/pets): keep the path
        // and leave the base URL for the user to choose.
        Matcher leading = VAR.matcher(base);
        if (leading.lookingAt()) {
            base = base.substring(leading.end());
            url.origin = null;
            url.pathTemplate = templatePath(base.isEmpty() ? "/" : base, url);
        } else {
            if (!base.contains("://")) {
                base = "http://" + base; // Postman's default when no protocol is given
            }
            int schemeEnd = base.indexOf("://");
            String scheme = base.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            int slash = base.indexOf('/', schemeEnd + 3);
            String hostPart = slash < 0 ? base.substring(schemeEnd + 3) : base.substring(schemeEnd + 3, slash);
            if (hostPart.isEmpty()) {
                return null;
            }
            url.origin = scheme + "://" + hostPart;
            url.pathTemplate = templatePath(slash < 0 ? "/" : base.substring(slash), url);
        }

        if (queryEntries != null) {
            for (JsonNode e : queryEntries) {
                if (e.isObject() && !e.path("disabled").asBoolean(false)) {
                    addQuery(ctx.resolve(e.path("key").asText("")), ctx.resolve(scalar(e.get("value"))), url);
                }
            }
        } else if (!queryString.isEmpty()) {
            for (String pair : queryString.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                addQuery(ctx.resolve(key), ctx.resolve(value), url);
            }
        }
        return url;
    }

    private static void addQuery(String key, String value, Url url) {
        String name = decodePercent(key);
        if (!name.isEmpty()) {
            url.query.add(new Param(name, Param.Location.QUERY, false, decodePercent(value)));
        }
    }

    /**
     * Turn Postman path variables ({@code :petId}) and undefined {@code {{vars}}} into
     * {@code {name}} templates, so they behave like OpenAPI path parameters.
     */
    private static String templatePath(String path, Url url) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.length() > 1 && segment.charAt(0) == ':') {
                segments.add("{" + segment.substring(1) + "}");
                continue;
            }
            Matcher m = VAR.matcher(segment);
            StringBuilder converted = new StringBuilder();
            while (m.find()) {
                String name = m.group(1).trim();
                url.unresolvedPathVars.add(name);
                m.appendReplacement(converted, Matcher.quoteReplacement("{" + name + "}"));
            }
            m.appendTail(converted);
            segments.add(converted.toString());
        }
        String result = String.join("/", segments);
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return result;
    }

    private static String fromParts(JsonNode url) {
        StringBuilder sb = new StringBuilder();
        String protocol = url.path("protocol").asText("");
        if (!protocol.isEmpty()) {
            sb.append(protocol).append("://");
        }
        sb.append(joined(url.get("host"), "."));
        String port = url.path("port").asText("");
        if (!port.isEmpty()) {
            sb.append(':').append(port);
        }
        String path = joined(url.get("path"), "/");
        if (!path.isEmpty()) {
            sb.append('/').append(path);
        }
        return sb.toString();
    }

    private static String joined(JsonNode node, String separator) {
        if (node == null) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode p : node) {
            parts.add(scalar(p));
        }
        return String.join(separator, parts);
    }

    // ---- Headers, body, auth ------------------------------------------------

    private static List<String[]> headers(JsonNode header, Context ctx) {
        List<String[]> out = new ArrayList<>();
        if (header == null) {
            return out;
        }
        if (header.isTextual()) { // "Key: Value" lines
            for (String line : header.asText().split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    out.add(new String[]{ctx.resolve(line.substring(0, colon).trim()),
                            ctx.resolve(line.substring(colon + 1).trim())});
                }
            }
            return out;
        }
        for (JsonNode h : header) {
            if (h.isObject() && !h.path("disabled").asBoolean(false)) {
                String key = ctx.resolve(h.path("key").asText("")).trim();
                if (!key.isEmpty()) {
                    out.add(new String[]{key, ctx.resolve(scalar(h.get("value")))});
                }
            }
        }
        return out;
    }

    /** @return {body, contentType}, or null for no body */
    private String[] body(JsonNode body, String mode, String headerContentType, Context ctx) {
        if (body == null || !body.isObject() || body.path("disabled").asBoolean(false)) {
            return null;
        }
        switch (mode) {
            case "raw": {
                String text = ctx.resolve(body.path("raw").asText(""));
                if (text.isEmpty()) {
                    return null;
                }
                String ct = headerContentType != null ? headerContentType
                        : contentTypeFor(body.path("options").path("raw").path("language").asText(""));
                return new String[]{text, ct};
            }
            case "urlencoded": {
                List<String> pairs = new ArrayList<>();
                for (JsonNode e : body.path("urlencoded")) {
                    if (e.isObject() && !e.path("disabled").asBoolean(false)) {
                        pairs.add(formEncode(ctx.resolve(e.path("key").asText(""))) + "="
                                + formEncode(ctx.resolve(scalar(e.get("value")))));
                    }
                }
                return new String[]{String.join("&", pairs),
                        headerContentType != null ? headerContentType : "application/x-www-form-urlencoded"};
            }
            case "formdata":
                return new String[]{multipart(body.path("formdata"), ctx),
                        "multipart/form-data; boundary=" + FORM_BOUNDARY};
            case "graphql": {
                ObjectNode gql = JsonNodeFactory.instance.objectNode();
                gql.put("query", ctx.resolve(body.path("graphql").path("query").asText("")));
                String vars = ctx.resolve(body.path("graphql").path("variables").asText(""));
                JsonNode parsed = null;
                if (!vars.isBlank()) {
                    try {
                        parsed = mapper.readTree(vars);
                    } catch (JsonProcessingException e) {
                        ctx.warnOnce("Some GraphQL variables were not valid JSON and were dropped.");
                    }
                }
                gql.set("variables", parsed != null && parsed.isObject()
                        ? parsed : JsonNodeFactory.instance.objectNode());
                return new String[]{gql.toString(), "application/json"};
            }
            case "file":
                ctx.warnOnce("File bodies are not supported; those requests are sent without a body.");
                return null;
            default:
                return null;
        }
    }

    private static String multipart(JsonNode fields, Context ctx) {
        StringBuilder sb = new StringBuilder();
        boolean files = false;
        for (JsonNode f : fields) {
            if (!f.isObject() || f.path("disabled").asBoolean(false)) {
                continue;
            }
            String name = headerSafe(ctx.resolve(f.path("key").asText("")));
            sb.append("--").append(FORM_BOUNDARY).append("\r\n");
            if ("file".equals(f.path("type").asText(""))) {
                files = true;
                String filename = headerSafe(lastPathPart(scalar(f.get("src"))));
                sb.append("Content-Disposition: form-data; name=\"").append(name)
                  .append("\"; filename=\"").append(filename.isEmpty() ? "file" : filename).append("\"\r\n")
                  .append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else {
                sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                  .append(ctx.resolve(scalar(f.get("value")))).append("\r\n");
            }
        }
        if (files) {
            ctx.warnOnce("Multipart file fields are sent as empty files; attach real content in Repeater.");
        }
        return sb.append("--").append(FORM_BOUNDARY).append("--\r\n").toString();
    }

    private List<SecurityRequirement> auth(JsonNode auth, Context ctx) {
        if (auth == null || !auth.isObject()) {
            return List.of();
        }
        String type = auth.path("type").asText("");
        Map<String, String> a = authParams(auth.get(type), ctx);
        switch (type) {
            case "noauth":
            case "":
                return List.of();
            case "bearer":
                return List.of(new SecurityRequirement("bearer", SecurityRequirement.Kind.HEADER,
                        "Authorization", "Bearer " + a.getOrDefault("token", "")));
            case "basic": {
                String credentials = a.getOrDefault("username", "") + ":" + a.getOrDefault("password", "");
                return List.of(new SecurityRequirement("basic", SecurityRequirement.Kind.HEADER,
                        "Authorization", "Basic " + Base64.getEncoder()
                                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8))));
            }
            case "apikey": {
                String key = a.getOrDefault("key", "");
                if (key.isEmpty()) {
                    key = "X-API-Key";
                }
                SecurityRequirement.Kind kind = "query".equalsIgnoreCase(a.getOrDefault("in", "header"))
                        ? SecurityRequirement.Kind.QUERY : SecurityRequirement.Kind.HEADER;
                return List.of(new SecurityRequirement("apikey", kind, key, a.getOrDefault("value", "")));
            }
            case "oauth2": {
                String token = a.getOrDefault("accessToken", "");
                if (token.isEmpty()) {
                    ctx.warnOnce("OAuth 2.0 tokens are not stored in the collection; set up an OAuth 2.0 "
                            + "auth profile to fetch one.");
                    token = "<token>";
                }
                String prefix = a.getOrDefault("headerPrefix", "Bearer");
                if ("queryParams".equals(a.get("addTokenTo"))) {
                    return List.of(new SecurityRequirement("oauth2", SecurityRequirement.Kind.QUERY,
                            "access_token", token));
                }
                return List.of(new SecurityRequirement("oauth2", SecurityRequirement.Kind.HEADER,
                        "Authorization", (prefix.isEmpty() ? "" : prefix + " ") + token));
            }
            default:
                ctx.warnOnce("Postman auth type \"" + headerSafe(type) + "\" is not supported; those "
                        + "requests are sent without it. Use an auth profile instead.");
                return List.of();
        }
    }

    /** v2.1 stores auth fields as [{key, value}]; v2.0 as an object. */
    private static Map<String, String> authParams(JsonNode node, Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode e : node) {
                String key = e.path("key").asText("");
                if (!key.isEmpty()) {
                    out.put(key, ctx.resolve(scalar(e.get("value"))));
                }
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), ctx.resolve(scalar(e.getValue()))));
        }
        return out;
    }

    // ---- Helpers -------------------------------------------------------------

    private static Map<String, String> collectionVariables(JsonNode root) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (JsonNode v : root.path("variable")) {
            if (v.isObject() && !v.path("disabled").asBoolean(false)) {
                String key = v.path("key").asText("");
                if (!key.isEmpty()) {
                    vars.put(key, scalar(v.get("value")));
                }
            }
        }
        return vars;
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String contentTypeFor(String language) {
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "html" -> "text/html";
            case "javascript" -> "application/javascript";
            default -> "text/plain";
        };
    }

    private static String formEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Percent-decode a value typed into Postman, so the request builder does not double-encode it. */
    private static String decodePercent(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** Keep untrusted text from breaking a multipart header line or a warning. */
    private static String headerSafe(String s) {
        return s.replaceAll("[\\r\\n\"]", "_");
    }

    private static String lastPathPart(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String listNames(Set<String> names) {
        List<String> shown = new ArrayList<>();
        for (String n : names) {
            if (shown.size() == MAX_LISTED_NAMES) {
                shown.add("… (" + (names.size() - MAX_LISTED_NAMES) + " more)");
                break;
            }
            shown.add(headerSafe(n));
        }
        return String.join(", ", shown);
    }

    /** Per-parse state: variables, results and warnings. */
    private static final class Context {
        final Map<String, String> environment;
        final Map<String, String> collectionVars;
        final List<Endpoint> endpoints = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> warned = new LinkedHashSet<>();
        final Set<String> unresolved = new LinkedHashSet<>();
        final Map<String, Integer> origins = new LinkedHashMap<>();
        int scripts;

        Context(Map<String, String> environment, Map<String, String> collectionVars) {
            this.environment = environment;
            this.collectionVars = collectionVars;
        }

        void warnOnce(String message) {
            if (warned.add(message)) {
                warnings.add(message);
            }
        }

        void countScripts(JsonNode node) {
            for (JsonNode event : node.path("event")) {
                JsonNode exec = event.path("script").path("exec");
                boolean nonEmpty = exec.isArray()
                        ? exec.size() > 0 && !String.join("", toList(exec)).isBlank()
                        : !exec.asText("").isBlank();
                if (nonEmpty) {
                    scripts++;
                }
            }
        }

        private static List<String> toList(JsonNode array) {
            List<String> out = new ArrayList<>();
            array.forEach(n -> out.add(n.asText("")));
            return out;
        }

        /** Expand {{vars}} (environment over collection), bounded in passes and size. */
        String resolve(String s) {
            if (s == null) {
                return "";
            }
            String current = s;
            for (int pass = 0; pass < MAX_VAR_PASSES && current.contains("{{"); pass++) {
                Matcher m = VAR.matcher(current);
                StringBuilder out = new StringBuilder();
                boolean changed = false;
                while (m.find()) {
                    String value = lookup(m.group(1).trim());
                    m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
                    changed |= value != null;
                    if (out.length() > MAX_EXPANDED) {
                        warnOnce("A variable expanded beyond " + (MAX_EXPANDED / 1024)
                                + " KiB; its expansion was stopped.");
                        return current;
                    }
                }
                m.appendTail(out);
                current = out.toString();
                if (!changed) {
                    break;
                }
            }
            Matcher left = VAR.matcher(current);
            while (left.find()) {
                unresolved.add(left.group(1).trim());
            }
            return current;
        }

        private String lookup(String name) {
            if (name.startsWith("$")) {
                return switch (name) {
                    case "$guid", "$randomUUID" -> UUID.randomUUID().toString();
                    case "$timestamp" -> Long.toString(Instant.now().getEpochSecond());
                    case "$isoTimestamp" -> Instant.now().toString();
                    case "$randomInt" -> Integer.toString(ThreadLocalRandom.current().nextInt(0, 1001));
                    default -> null;
                };
            }
            String value = environment.get(name);
            return value != null ? value : collectionVars.get(name);
        }
    }
}
