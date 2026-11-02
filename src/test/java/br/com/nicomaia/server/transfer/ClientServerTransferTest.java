package br.com.nicomaia.server.transfer;

import static org.junit.jupiter.api.Assertions.*;

import br.com.nicomaia.server.metrics.Metrics;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientServerTransferTest {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  /** The relay's own ends of the two connections. */
  private Socket transferClientSide;

  private Socket transferServerSide;

  /** The far ends, playing the SOCKS client and the destination. */
  private Socket testClientSide;

  private Socket testServerSide;

  @BeforeEach
  void connect() throws IOException {
    try (ServerSocket clientListener = new ServerSocket(0, 50, LOOPBACK);
        ServerSocket serverListener = new ServerSocket(0, 50, LOOPBACK)) {
      transferClientSide = new Socket(LOOPBACK, clientListener.getLocalPort());
      testClientSide = clientListener.accept();
      transferServerSide = new Socket(LOOPBACK, serverListener.getLocalPort());
      testServerSide = serverListener.accept();
    }
    testClientSide.setSoTimeout(3000);
    testServerSide.setSoTimeout(3000);
  }

  @AfterEach
  void close() throws IOException {
    for (Socket socket :
        new Socket[] {transferClientSide, transferServerSide, testClientSide, testServerSide}) {
      socket.close();
    }
  }

  @Test
  void shouldBlockUntilBothDirectionsFinishAndCountBytes() throws Exception {
    Metrics metrics = Metrics.instance();
    long upBefore = metrics.bytesUploaded();
    long downBefore = metrics.bytesDownloaded();

    Thread starter = startRelay(metrics, ClientServerTransfer.DEFAULT_IDLE_TIMEOUT);

    byte[] upload = "hello-upload".getBytes();
    byte[] download = "hi-download".getBytes();

    testClientSide.getOutputStream().write(upload);
    testClientSide.getOutputStream().flush();
    testServerSide.getOutputStream().write(download);
    testServerSide.getOutputStream().flush();

    // Confirm both directions actually relayed the bytes.
    assertArrayEquals(upload, testServerSide.getInputStream().readNBytes(upload.length));
    assertArrayEquals(download, testClientSide.getInputStream().readNBytes(download.length));

    // The relay is live and both sockets are open, so start() must still be blocking.
    assertTrue(starter.isAlive(), "start() must block while the connection is open");

    // Closing both ends makes each direction hit EOF, so start() returns.
    testClientSide.close();
    testServerSide.close();

    starter.join(3000);
    assertFalse(starter.isAlive(), "start() must return only after both directions finish");

    assertTrue(metrics.bytesUploaded() >= upBefore + upload.length);
    assertTrue(metrics.bytesDownloaded() >= downBefore + download.length);
  }

  @Test
  void shouldCloseBothSocketsWhenInterrupted() throws Exception {
    Thread starter = startRelay(Metrics.instance(), ClientServerTransfer.DEFAULT_IDLE_TIMEOUT);

    starter.interrupt();
    starter.join(3000);

    assertFalse(starter.isAlive(), "start() must return when interrupted");
    assertTrue(transferClientSide.isClosed(), "Client socket must be closed on interrupt");
    assertTrue(transferServerSide.isClosed(), "Server socket must be closed on interrupt");
  }

  @Test
  void shouldCloseRelayAfterIdleTimeout() throws Exception {
    Thread starter = startRelay(Metrics.instance(), Duration.ofSeconds(1));

    Thread.sleep(100);
    assertTrue(starter.isAlive(), "The relay must not close before the idle timeout");

    starter.join(3000);
    assertFalse(starter.isAlive(), "An idle relay must be closed after the idle timeout");
    assertTrue(transferClientSide.isClosed());
    assertTrue(transferServerSide.isClosed());
    // Both peers see the connection end.
    assertEquals(-1, testClientSide.getInputStream().read());
    assertEquals(-1, testServerSide.getInputStream().read());
  }

  @Test
  void shouldKeepRelayOpenWhileOnlyOneDirectionHasTraffic() throws Exception {
    Duration idleTimeout = Duration.ofMillis(1500);
    Thread starter = startRelay(Metrics.instance(), idleTimeout);

    // A long one-way download: the upload direction stays silent for well over the timeout,
    // but the relay as a whole is not idle.
    OutputStream destination = testServerSide.getOutputStream();
    for (int i = 0; i < 12; i++) {
      destination.write(i);
      destination.flush();
      assertEquals(i, testClientSide.getInputStream().read());
      Thread.sleep(200);
    }

    assertTrue(starter.isAlive(), "Traffic in one direction must keep the relay open");
  }

  @Test
  void shouldNeverTimeOutWhenIdleTimeoutIsDisabled() throws Exception {
    Thread starter = startRelay(Metrics.instance(), Duration.ZERO);

    Thread.sleep(500);

    assertTrue(starter.isAlive(), "Duration.ZERO must disable the idle timeout");
  }

  @Test
  void shouldCloseRelayStuckWritingToAPeerThatStoppedReading() throws Exception {
    // The destination floods the relay while the client never reads: once the socket buffers
    // fill, the relay blocks in write(), where no read timeout could ever fire. The watchdog
    // must still tear it down.
    Thread starter = startRelay(Metrics.instance(), Duration.ofMillis(500));

    Thread flooder =
        Thread.ofVirtual()
            .start(
                () -> {
                  byte[] chunk = new byte[64 * 1024];
                  try {
                    OutputStream out = testServerSide.getOutputStream();
                    while (true) {
                      out.write(chunk);
                    }
                  } catch (IOException expected) {
                    // the relay closed the connection
                  }
                });

    starter.join(10_000);
    assertFalse(starter.isAlive(), "A relay blocked on a stalled peer must be closed when idle");
    assertTrue(transferClientSide.isClosed());
    assertTrue(transferServerSide.isClosed());

    testServerSide.close(); // unblock the flooder if it is still writing
    flooder.join(3000);
  }

  @Test
  void shouldEnableKeepAliveOnBothSockets() throws Exception {
    Thread starter = startRelay(Metrics.instance(), ClientServerTransfer.DEFAULT_IDLE_TIMEOUT);

    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!(transferClientSide.getKeepAlive() && transferServerSide.getKeepAlive())
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    assertTrue(transferClientSide.getKeepAlive(), "Client socket must have SO_KEEPALIVE");
    assertTrue(transferServerSide.getKeepAlive(), "Server socket must have SO_KEEPALIVE");
    if (transferClientSide.supportedOptions().contains(ExtendedSocketOptions.TCP_KEEPIDLE)) {
      assertEquals(
          ClientServerTransfer.KEEPALIVE_IDLE_SECONDS,
          transferClientSide.getOption(ExtendedSocketOptions.TCP_KEEPIDLE));
    }
    assertTrue(starter.isAlive());
  }

  @Test
  void shouldRejectIdleTimeoutTooLargeForNanoseconds() {
    // start() works in nanoseconds; an overflow must fail at construction, not mid-relay.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientServerTransfer(
                transferClientSide,
                transferServerSide,
                Metrics.instance(),
                Duration.ofDays(1_000_000)));
  }

  @Test
  void shouldRejectNegativeIdleTimeout() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientServerTransfer(
                transferClientSide,
                transferServerSide,
                Metrics.instance(),
                Duration.ofSeconds(-1)));
  }

  private Thread startRelay(Metrics metrics, Duration idleTimeout) {
    ClientServerTransfer transfer =
        new ClientServerTransfer(transferClientSide, transferServerSide, metrics, idleTimeout);
    return Thread.ofVirtual().start(transfer::start);
  }
}
