package io.github.r0xll.betterswagger.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyValueHarvesterTest {

    private static Endpoint endpoint(String path, String... queryNames) {
        List<Param> params = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}]+)\\}").matcher(path);
        while (m.find()) {
            params.add(new Param(m.group(1), Param.Location.PATH, true, "0"));
        }
        for (String q : queryNames) {
            params.add(new Param(q, Param.Location.QUERY, false, "string"));
        }
        return new Endpoint("GET", path, "", params, List.of(), null, null);
    }

    private final List<Endpoint> endpoints = List.of(
            endpoint("/pets/{petId}"),
            endpoint("/pets/mine"),
            endpoint("/users/{id}/orders/{orderId}"),
            endpoint("/search", "q"),
            endpoint("/files/v{version}.json"));

    @Test
    void capturesPathValuesUnderTheBasePath() {
        ProxyValueHarvester h = new ProxyValueHarvester(endpoints, "/v1");
        h.observe("/v1/pets/1337");
        h.observe("/v1/users/7/orders/99?x=1");
        h.observe("/v1/files/v3.json");
        Map<String, Map<String, String>> v = h.values();
        assertEquals("1337", v.get("/pets/{petId}").get("petId"));
        assertEquals("7", v.get("/users/{id}/orders/{orderId}").get("id"));
        assertEquals("99", v.get("/users/{id}/orders/{orderId}").get("orderId"));
        assertEquals("3", v.get("/files/v{version}.json").get("version"));
        assertEquals(3, h.matched());
    }

    @Test
    void mostSpecificTemplateWinsAndOtherBasePathsAreIgnored() {
        ProxyValueHarvester h = new ProxyValueHarvester(endpoints, "/v1");
        h.observe("/v1/pets/mine");
        h.observe("/v2/pets/5");
        h.observe("/v1/pets");
        assertNull(h.values().get("/pets/{petId}"), "/pets/mine must not be captured as a petId");
        assertEquals(1, h.matched());
    }

    @Test
    void lastSeenWinsAndValuesAreDecoded() {
        ProxyValueHarvester h = new ProxyValueHarvester(endpoints, "");
        h.observe("/pets/1");
        h.observe("/pets/a%2Fb");
        assertEquals("a/b", h.values().get("/pets/{petId}").get("petId"));
    }

    @Test
    void capturesOnlyDeclaredQueryParameters() {
        ProxyValueHarvester h = new ProxyValueHarvester(endpoints, "");
        h.observe("/search?q=foo%20bar&other=1");
        assertEquals(Map.of("q", "foo bar"), h.values().get("/search"));
    }

    @Test
    void regexCharactersInTemplatesAreLiteral() {
        ProxyValueHarvester h = new ProxyValueHarvester(List.of(endpoint("/a.b/{x}")), "");
        h.observe("/aXb/1");
        h.observe("/a.b/2");
        assertEquals("2", h.values().get("/a.b/{x}").get("x"));
        assertEquals(1, h.matched());
    }
}
