package io.github.r0xll.betterswagger.core;

/** Thrown when a spec cannot be loaded or parsed, or violates a hardening limit. */
public final class SpecException extends RuntimeException {

    public SpecException(String message) {
        super(message);
    }

    public SpecException(String message, Throwable cause) {
        super(message, cause);
    }
}
