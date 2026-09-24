package br.com.nicomaia.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UsernamePasswordRequestTest {

  @Test
  void shouldStoreAllFields() {
    var request =
        new UsernamePasswordRequest(
            (byte) 0x01, "alice".getBytes(StandardCharsets.UTF_8), "s3cret".getBytes(StandardCharsets.UTF_8));

    assertEquals((byte) 0x01, request.version());
    assertArrayEquals("alice".getBytes(StandardCharsets.UTF_8), request.username());
    assertArrayEquals("s3cret".getBytes(StandardCharsets.UTF_8), request.password());
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
    var request =
        new UsernamePasswordRequest(
            (byte) 0x01, "alice".getBytes(StandardCharsets.UTF_8), "s3cret".getBytes(StandardCharsets.UTF_8));

    request.username()[0] = 'X';
    request.password()[0] = 'X';

    assertEquals('a', request.username()[0]);
    assertEquals('s', request.password()[0]);
  }

  @Test
  void shouldNotLeakCredentialsInToString() {
    var request =
        new UsernamePasswordRequest(
            (byte) 0x01, "alice".getBytes(StandardCharsets.UTF_8), "s3cret".getBytes(StandardCharsets.UTF_8));

    String result = request.toString();

    assertFalse(result.contains("alice"));
    assertFalse(result.contains("s3cret"));
  }
}
