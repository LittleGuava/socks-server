package br.com.nicomaia.server.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Socks5CredentialsTest {

  @Test
  void shouldCreateCredentialsFromValidUsernameAndPassword() {
    var credentials = Socks5Credentials.of("alice", "s3cret");

    assertTrue(credentials.matches(bytes("alice"), bytes("s3cret")));
  }

  @Test
  void shouldRejectMismatchedUsername() {
    var credentials = Socks5Credentials.of("alice", "s3cret");

    assertFalse(credentials.matches(bytes("bob"), bytes("s3cret")));
  }

  @Test
  void shouldRejectMismatchedPassword() {
    var credentials = Socks5Credentials.of("alice", "s3cret");

    assertFalse(credentials.matches(bytes("alice"), bytes("wrong")));
  }

  @Test
  void shouldRejectWhenBothFieldsMismatch() {
    var credentials = Socks5Credentials.of("alice", "s3cret");

    assertFalse(credentials.matches(bytes("bob"), bytes("wrong")));
  }

  @Test
  void shouldThrowWhenUsernameIsMissing() {
    var exception =
        assertThrows(IllegalStateException.class, () -> Socks5Credentials.of(null, "s3cret"));

    assertTrue(exception.getMessage().contains(Socks5Credentials.USERNAME_ENV));
  }

  @Test
  void shouldThrowWhenUsernameIsBlank() {
    assertThrows(IllegalStateException.class, () -> Socks5Credentials.of("   ", "s3cret"));
  }

  @Test
  void shouldThrowWhenPasswordIsMissing() {
    var exception =
        assertThrows(IllegalStateException.class, () -> Socks5Credentials.of("alice", null));

    assertTrue(exception.getMessage().contains(Socks5Credentials.PASSWORD_ENV));
  }

  @Test
  void shouldThrowWhenPasswordIsBlank() {
    assertThrows(IllegalStateException.class, () -> Socks5Credentials.of("alice", "   "));
  }

  @Test
  void shouldNotBeAffectedByMutatingSourceBytes() {
    var credentials = Socks5Credentials.of("alice", "s3cret");
    byte[] candidateUsername = bytes("alice");

    boolean firstCheck = credentials.matches(candidateUsername, bytes("s3cret"));
    candidateUsername[0] = 'X';
    boolean secondCheck = credentials.matches(candidateUsername, bytes("s3cret"));

    assertTrue(firstCheck);
    assertFalse(secondCheck);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
