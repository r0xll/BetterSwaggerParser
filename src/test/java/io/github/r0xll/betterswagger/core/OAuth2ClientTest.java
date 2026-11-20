package io.github.r0xll.betterswagger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2ClientTest {

    private static AuthProfile profile(AuthProfile.Type type, boolean basicClientAuth) {
        return new AuthProfile("o", type, "", "alice", "p@ss word", "", null, "",
                "https://auth.example.com/token", "my-client", "s&c", "read write", basicClientAuth, "", 0);
    }

    @Test
    void clientCredentialsWithSecretInBody() {
        AuthProfile p = profile(AuthProfile.Type.OAUTH2_CLIENT_CREDENTIALS, false);
        assertEquals("grant_type=client_credentials&client_id=my-client&client_secret=s%26c&scope=read+write",
                OAuth2Client.requestBody(p));
        assertFalse(OAuth2Client.requestHeaders(p).containsKey("Authorization"));
    }

    @Test
    void clientAuthInBasicHeader() {
        AuthProfile p = profile(AuthProfile.Type.OAUTH2_CLIENT_CREDENTIALS, true);
        assertEquals("grant_type=client_credentials&scope=read+write", OAuth2Client.requestBody(p));
        // Basic base64("my-client:s%26c")
        assertEquals("Basic bXktY2xpZW50OnMlMjZj", OAuth2Client.requestHeaders(p).get("Authorization"));
        assertEquals("application/x-www-form-urlencoded", OAuth2Client.requestHeaders(p).get("Content-Type"));
    }

    @Test
    void passwordGrantSendsUserCredentials() {
        String body = OAuth2Client.requestBody(profile(AuthProfile.Type.OAUTH2_PASSWORD, false));
        assertTrue(body.startsWith("grant_type=password&username=alice&password=p%40ss+word&"), body);
    }

    @Test
    void parsesTokenAndLifetime() {
        OAuth2Client.Token t = OAuth2Client.parseResponse(
                "{\"access_token\":\"eyJ.abc.def\",\"token_type\":\"Bearer\",\"expires_in\":3600}", 1_000);
        assertEquals("eyJ.abc.def", t.accessToken());
        assertEquals(1_000 + 3_600_000, t.expiresAtMillis());
        assertEquals(0, OAuth2Client.parseResponse("{\"access_token\":\"x\"}", 1_000).expiresAtMillis());
    }

    @Test
    void rejectsErrorsAndUnsafeTokens() {
        SpecException err = assertThrows(SpecException.class, () -> OAuth2Client.parseResponse(
                "{\"error\":\"invalid_client\",\"error_description\":\"bad\\r\\nsecret\"}", 0));
        assertTrue(err.getMessage().contains("invalid_client"));
        assertFalse(err.getMessage().contains("\r") || err.getMessage().contains("\n"));

        assertThrows(SpecException.class, () -> OAuth2Client.parseResponse("{\"token_type\":\"x\"}", 0));
        assertThrows(SpecException.class, () -> OAuth2Client.parseResponse(
                "{\"access_token\":\"a\\r\\nX-Injected: 1\"}", 0));
        assertThrows(SpecException.class, () -> OAuth2Client.parseResponse("<html>login</html>", 0));
        assertThrows(SpecException.class, () -> OAuth2Client.parseResponse("", 0));
    }
}
