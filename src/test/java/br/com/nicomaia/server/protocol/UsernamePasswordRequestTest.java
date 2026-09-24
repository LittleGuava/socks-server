package br.com.nicomaia.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UsernamePasswordRequestTest {

  private static final byte[] USERNAME_BYTES = "alice".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PASSWORD_BYTES = "s3cret".getBytes(StandardCharsets.UTF_8);

  @Test
  void shouldStoreAllFields() {
    var request = new UsernamePasswordRequest((byte) 0x01, USERNAME_BYTES, PASSWORD_BYTES);

    assertEquals((byte) 0x01, request.version());
    assertArrayEquals(USERNAME_BYTES, request.username());
    assertArrayEquals(PASSWORD_BYTES, request.password());
  }

  @Test
  void shouldDefensivelyCopyConstructorArrays() {
    byte[] username = "alice".getBytes(StandardCharsets.UTF_8);
    byte[] password = "s3cret".getBytes(StandardCharsets.UTF_8);
    var request = new UsernamePasswordRequest((byte) 0x01, username, password);

    username[0] = 'X';
    password[0] = 'X';

    assertEquals('a', request.username()[0]);
    assertEquals('s', request.password()[0]);
  }

  @Test
  void shouldDefensivelyCopyOnAccess() {
    var request = new UsernamePasswordRequest((byte) 0x01, USERNAME_BYTES, PASSWORD_BYTES);

    request.username()[0] = 'X';
    request.password()[0] = 'X';

    assertEquals('a', request.username()[0]);
    assertEquals('s', request.password()[0]);
  }

  @Test
  void shouldNotLeakCredentialsInToString() {
    var request = new UsernamePasswordRequest((byte) 0x01, USERNAME_BYTES, PASSWORD_BYTES);

    String result = request.toString();

    assertFalse(result.contains("alice"));
    assertFalse(result.contains("s3cret"));
  }
}
