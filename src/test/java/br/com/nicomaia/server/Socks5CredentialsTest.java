package br.com.nicomaia.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Socks5CredentialsTest {
    @Test
    void matchesConfiguredCredentials() {
        Socks5Credentials credentials = new Socks5Credentials("proxy", "secret");

        assertTrue(credentials.matches(bytes("proxy"), bytes("secret")));
        assertFalse(credentials.matches(bytes("other"), bytes("secret")));
        assertFalse(credentials.matches(bytes("proxy"), bytes("wrong")));
    }

    @Test
    void rejectsCredentialsThatCannotFitInSocks5Fields() {
        assertThrows(IllegalArgumentException.class, () -> new Socks5Credentials("u".repeat(256), "p"));
        assertThrows(IllegalArgumentException.class, () -> new Socks5Credentials("u", "p".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> new Socks5Credentials("", "p"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
