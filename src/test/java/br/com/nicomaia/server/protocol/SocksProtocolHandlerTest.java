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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SocksProtocolHandlerTest {

  private static final Socks5Credentials CREDENTIALS = Socks5Credentials.of("alice", "s3cret");

  @Test
  void shouldRejectClientThatOnlyOffersNoAuth() throws Exception {
    try (var harness = Harness.start(CREDENTIALS)) {
      Socket client = harness.connectClient();
      OutputStream out = client.getOutputStream();

      out.write(new byte[] {0x05, 0x01, 0x00}); // VER, NMETHODS, NO_AUTH
      out.flush();

      byte[] response = client.getInputStream().readNBytes(2);
      assertArrayEquals(new byte[] {0x05, (byte) 0xFF}, response);
      assertEquals(-1, client.getInputStream().read());
      assertFalse(harness.commandDispatched.get());
    }
  }

  @Test
  void shouldRejectInvalidCredentials() throws Exception {
    try (var harness = Harness.start(CREDENTIALS)) {
      Socket client = harness.connectClient();
      OutputStream out = client.getOutputStream();

      out.write(new byte[] {0x05, 0x01, 0x02}); // VER, NMETHODS, USERNAME
      out.flush();
      assertArrayEquals(new byte[] {0x05, 0x02}, client.getInputStream().readNBytes(2));

      writeUsernamePassword(out, "alice", "wrong-password");

      byte[] authStatus = client.getInputStream().readNBytes(2);
      assertArrayEquals(new byte[] {0x01, 0x01}, authStatus);
      assertEquals(-1, client.getInputStream().read());
      assertFalse(harness.commandDispatched.get());
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
    private final AtomicBoolean commandDispatched = new AtomicBoolean(false);
    private final CountDownLatch commandLatch = new CountDownLatch(1);

    private Harness(ServerSocket serverSocket, Socks5Credentials credentials) {
      this.serverSocket = serverSocket;

      Map<AddressType, InetResolver> resolvers = Map.of(AddressType.IPV4, new IpInetResolver());
      var addressResolver = new AddressResolver(resolvers);
      var handlers = new HandlersHolder();
      handlers.register(
          CommandType.CONNECT,
          (clientSocket, command) -> {
            commandDispatched.set(true);
            commandLatch.countDown();
          });

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
