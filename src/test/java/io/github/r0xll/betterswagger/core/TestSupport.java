package io.github.r0xll.betterswagger.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class TestSupport {
    private TestSupport() { }

    static byte[] fixture(String name) {
        try (InputStream in = TestSupport.class.getResourceAsStream("/specs/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String fixtureText(String name) {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }
}
