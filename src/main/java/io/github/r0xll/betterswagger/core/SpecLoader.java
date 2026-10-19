package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Turns untrusted spec bytes into a Jackson {@link JsonNode} tree, with hard
 * limits that keep a hostile document from exhausting memory or CPU (BApp rule 1:
 * HTTP message content is untrusted).
 *
 * <p>Defenses:
 * <ul>
 *   <li>Input size is capped before parsing.</li>
 *   <li>Only the tree model is used &mdash; never POJO or polymorphic
 *       deserialization &mdash; so no gadget types can be instantiated.</li>
 *   <li>Jackson {@link StreamReadConstraints} bound nesting depth, token length
 *       and total document length.</li>
 *   <li>For YAML, SnakeYAML {@link LoaderOptions} bound total code points and
 *       nesting depth and forbid recursive keys. Note that Jackson's YAML tree
 *       reader does not expand anchors/aliases into their referenced content
 *       (an alias becomes its anchor name as a scalar), so alias-amplification
 *       ("billion-laughs") cannot explode the tree; the alias cap is kept as
 *       defense in depth.</li>
 * </ul>
 */
public final class SpecLoader {

    /** Hard ceiling on the raw document size we will even attempt to parse. */
    public static final int MAX_INPUT_BYTES = 25 * 1024 * 1024;

    private static final int MAX_NESTING_DEPTH = 200;
    private static final int MAX_STRING_LENGTH = 20_000_000;
    private static final int MAX_YAML_ALIASES = 50;
    private static final int MAX_YAML_CODEPOINTS = MAX_INPUT_BYTES;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public SpecLoader() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxDocumentLength(MAX_INPUT_BYTES)
                .build();

        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.getFactory().setStreamReadConstraints(constraints);

        LoaderOptions yamlOptions = new LoaderOptions();
        yamlOptions.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        yamlOptions.setAllowRecursiveKeys(false);
        yamlOptions.setProcessComments(false);
        yamlOptions.setNestingDepthLimit(MAX_NESTING_DEPTH);
        yamlOptions.setCodePointLimit(MAX_YAML_CODEPOINTS);

        YAMLFactory yamlFactory = YAMLFactory.builder()
                .loaderOptions(yamlOptions)
                .build();
        yamlFactory.setStreamReadConstraints(constraints);
        this.yamlMapper = new YAMLMapper(yamlFactory);
    }

    /**
     * Parse the given bytes as either JSON or YAML (sniffed from the content).
     *
     * @throws SpecException if the input is too large, empty, or not valid
     *                       JSON/YAML, or if a hardening limit is exceeded.
     */
    public JsonNode load(byte[] data) {
        if (data == null || data.length == 0) {
            throw new SpecException("Spec is empty.");
        }
        if (data.length > MAX_INPUT_BYTES) {
            throw new SpecException("Spec is too large (" + data.length
                    + " bytes, limit " + MAX_INPUT_BYTES + ").");
        }

        String text = new String(data, StandardCharsets.UTF_8);
        boolean looksJson = looksLikeJson(text);
        try {
            return (looksJson ? jsonMapper : yamlMapper).readTree(text);
        } catch (IOException | RuntimeException primary) {
            // Sniffing can be wrong (e.g. JSON is valid YAML but not vice-versa);
            // fall back to the other parser before giving up.
            try {
                return (looksJson ? yamlMapper : jsonMapper).readTree(text);
            } catch (IOException | RuntimeException secondary) {
                throw new SpecException("Could not parse spec as JSON or YAML: "
                        + primary.getMessage(), primary);
            }
        }
    }

    public JsonNode load(String text) {
        return load(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean looksLikeJson(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '{' || c == '[';
        }
        return false;
    }
}
