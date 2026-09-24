package br.com.nicomaia.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Socks5Credentials {
    private final byte[] username;
    private final byte[] password;

    Socks5Credentials(String username, String password) {
        this.username = encodeAndValidate(username, "username");
        this.password = encodeAndValidate(password, "password");
    }

    static Socks5Credentials fromEnvironment() {
        String username = System.getenv("nexus_deps_USR");
        String password = System.getenv("nexus_deps_psw");
        if (username == null || password == null) {
            throw new IllegalStateException(
                    "Set both nexus_deps_USR and nexus_deps_psw environment variables before starting the server");
        }
        return new Socks5Credentials(username, password);
    }

    boolean matches(byte[] suppliedUsername, byte[] suppliedPassword) {
        boolean usernameMatches = MessageDigest.isEqual(username, suppliedUsername);
        boolean passwordMatches = MessageDigest.isEqual(password, suppliedPassword);
        return usernameMatches & passwordMatches;
    }

    private static byte[] encodeAndValidate(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 255) {
            throw new IllegalArgumentException(field + " must be at most 255 UTF-8 bytes");
        }
        return encoded;
    }
}
