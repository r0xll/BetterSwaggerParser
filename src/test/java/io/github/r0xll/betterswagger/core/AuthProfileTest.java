package io.github.r0xll.betterswagger.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthProfileTest {

    private final SpecLoader loader = new SpecLoader();
    private final RequestBuilder builder = new RequestBuilder();

    private Endpoint endpoint(String fixture, String method, String path) {
        ApiSpec spec = new SpecParser().parse(loader.load(TestSupport.fixture(fixture)), null).spec();
        return spec.endpoints().stream().filter(e -> e.method().equals(method) && e.path().equals(path))
                .findFirst().orElseThrow();
    }

    private static AuthProfile apiKey(AuthProfile.KeyLocation where) {
        return new AuthProfile("k", AuthProfile.Type.API_KEY, "", "", "", "api_key", where, "k-123",
                "", "", "", "", false, "", 0);
    }

    private static long count(String raw, String needle) {
        return raw.lines().filter(l -> l.toLowerCase().startsWith(needle.toLowerCase())).count();
    }

    @Test
    void bearerReplacesTheSpecPlaceholder() {
        Endpoint post = endpoint("petstore-oas30.yaml", "POST", "/pets");
        AuthProfile p = new AuthProfile("u1", AuthProfile.Type.BEARER, "abc", "", "", "", null, "",
                "", "", "", "", false, "", 0);
        String raw = builder.build(post, "https://api.example.com/v3", Map.of(), p.values()).raw;
        assertTrue(raw.contains("Authorization: Bearer abc\r\n"), raw);
        assertFalse(raw.contains("<token>"));

        AuthProfile pasted = new AuthProfile("u1", AuthProfile.Type.BEARER, "Bearer xyz", "", "", "", null, "",
                "", "", "", "", false, "", 0);
        assertEquals("Bearer xyz", pasted.values().headers().get("Authorization"), "no double prefix");
    }

    @Test
    void basicIsEncoded() {
        AuthProfile p = new AuthProfile("b", AuthProfile.Type.BASIC, "", "alice", "s3cret", "", null, "",
                "", "", "", "", false, "", 0);
        assertEquals("Basic YWxpY2U6czNjcmV0", p.values().headers().get("Authorization"));
    }

    @Test
    void apiKeyInQueryReplacesSpecAuthAndDuplicates() {
        Endpoint get = endpoint("petstore-swagger2.json", "GET", "/pets/{petId}");
        Endpoint withParam = new Endpoint(get.method(), get.path(), get.summary(),
                List.of(new Param("api_key", Param.Location.QUERY, false, "old")), get.security(), null, null);
        String raw = builder.build(withParam, "https://api.example.com/v2", Map.of(),
                apiKey(AuthProfile.KeyLocation.QUERY).values()).raw;
        String requestLine = raw.substring(0, raw.indexOf("\r\n"));
        assertEquals("GET /v2/pets/%7BpetId%7D?api_key=k-123 HTTP/1.1", requestLine);
        assertFalse(raw.contains("X-API-Key"), "the spec's own API key placeholder is replaced");
    }

    @Test
    void apiKeyInCookie() {
        Endpoint get = endpoint("petstore-oas30.yaml", "GET", "/pets");
        String raw = builder.build(get, "https://api.example.com/v3", Map.of(),
                apiKey(AuthProfile.KeyLocation.COOKIE).values()).raw;
        assertTrue(raw.contains("Cookie: api_key=k-123\r\n"), raw);
    }

    @Test
    void customHeadersStillWinAndHeadersAreCaseInsensitive() {
        Endpoint ep = new Endpoint("GET", "/p", "",
                List.of(new Param("authorization", Param.Location.HEADER, false, "from-spec")),
                List.of(), null, null);
        AuthProfile p = new AuthProfile("u", AuthProfile.Type.BEARER, "profile", "", "", "", null, "",
                "", "", "", "", false, "", 0);

        String withProfile = builder.build(ep, "https://h.example.com", Map.of(), p.values()).raw;
        assertEquals(1, count(withProfile, "authorization:"));
        assertTrue(withProfile.contains("Authorization: Bearer profile\r\n"));

        String withCustom = builder.build(ep, "https://h.example.com",
                Map.of("AUTHORIZATION", "Bearer custom"), p.values()).raw;
        assertEquals(1, count(withCustom, "authorization:"));
        assertTrue(withCustom.contains("AUTHORIZATION: Bearer custom\r\n"));
    }

    @Test
    void oauthProfileWithoutTokenKeepsSpecAuth() {
        AuthProfile oauth = AuthProfile.of("o", AuthProfile.Type.OAUTH2_CLIENT_CREDENTIALS);
        assertTrue(oauth.values().isEmpty());
        assertTrue(oauth.needsToken(1000));
        AuthProfile fresh = oauth.withToken("tok", 10_000);
        assertFalse(fresh.needsToken(5_000));
        assertTrue(fresh.needsToken(10_000), "expired");
        assertEquals("Bearer tok", fresh.values().headers().get("Authorization"));
        assertFalse(oauth.withToken("tok", 0).needsToken(Long.MAX_VALUE), "no lifetime: never expires");
    }
}
