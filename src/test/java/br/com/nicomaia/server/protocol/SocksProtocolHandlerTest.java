package br.com.nicomaia.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import br.com.nicomaia.server.auth.Socks5Credentials;
import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import br.com.nicomaia.server.net.resolvers.InetResolver;
import br.com.nicomaia.server.net.resolvers.IpInetResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Covers the end-to-end wiring between authentication and command dispatch over a real socket —
 * the one thing {@link Socks5AuthenticatorTest} (plain streams) can't prove. Auth edge cases
 * (rejected methods, wrong credentials, protocol version handling) live there instead, since they
 * don't need a socket at all.
 */
class SocksProtocolHandlerTest {

  private static final Socks5Credentials CREDENTIALS = Socks5Credentials.of("alice", "s3cret");

  @Test
  void shouldNotDispatchCommandWhenAuthenticationFails() throws Exception {
    try (var harness = Harness.start(CREDENTIALS)) {
      Socket client = harness.connectClient();
      OutputStream out = client.getOutputStream();

      out.write(new byte[] {0x05, 0x01, 0x00}); // VER, NMETHODS, NO_AUTH only
      out.flush();

      assertArrayEquals(new byte[] {0x05, (byte) 0xFF}, client.getInputStream().readNBytes(2));
      assertEquals(-1, client.getInputStream().read());
      assertFalse(harness.awaitCommandDispatched(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  void shouldAuthenticateAndDispatchCommand() throws Exception {
    try (var harness = Harness.start(CREDENTIALS)) {
      Socket client = harness.connectClient();
      OutputStream out = client.getOutputStream();

      out.write(new byte[] {0x05, 0x01, 0x02});
      out.flush();
      assertArrayEquals(new byte[] {0x05, 0x02}, client.getInputStream().readNBytes(2));

      writeUsernamePassword(out, "alice", "s3cret");
      assertArrayEquals(new byte[] {0x01, 0x00}, client.getInputStream().readNBytes(2));

      // CONNECT to 127.0.0.1:80
      out.write(new byte[] {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
      out.flush();

      assertTrue(harness.awaitCommandDispatched(2, TimeUnit.SECONDS));
    }
  }

  private static void writeUsernamePassword(OutputStream out, String username, String password)
      throws IOException {
    byte[] u = username.getBytes(StandardCharsets.UTF_8);
    byte[] p = password.getBytes(StandardCharsets.UTF_8);
    out.write(0x01);
    out.write(u.length);
    out.write(u);
    out.write(p.length);
    out.write(p);
    out.flush();
  }

  /** Spins up a real loopback server socket wired to a {@link SocksProtocolHandler}. */
  private static final class Harness implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final Thread serverThread;
    private final CountDownLatch commandLatch = new CountDownLatch(1);

    private Harness(ServerSocket serverSocket, Socks5Credentials credentials) {
      this.serverSocket = serverSocket;

      Map<AddressType, InetResolver> resolvers = Map.of(AddressType.IPV4, new IpInetResolver());
      var addressResolver = new AddressResolver(resolvers);
      var handlers = new HandlersHolder();
      handlers.register(
          CommandType.CONNECT, (clientSocket, command) -> commandLatch.countDown());

      var protocolHandler = new SocksProtocolHandler(addressResolver, handlers, credentials);

      this.serverThread =
          new Thread(
              () -> {
                try {
                  Socket accepted = serverSocket.accept();
                  protocolHandler.handle(accepted);
                } catch (IOException ignored) {
                  // Server socket closed during test teardown.
                }
              });
      serverThread.setDaemon(true);
      serverThread.start();
    }

    static Harness start(Socks5Credentials credentials) throws IOException {
      return new Harness(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()), credentials);
    }

    Socket connectClient() throws IOException {
      return new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
    }

    boolean awaitCommandDispatched(long timeout, TimeUnit unit) throws InterruptedException {
      return commandLatch.await(timeout, unit);
    }

    @Override
    public void close() throws IOException {
      serverSocket.close();
    }
  }
}
