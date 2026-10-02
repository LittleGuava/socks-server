package br.com.nicomaia.server.transfer;

import br.com.nicomaia.server.metrics.Metrics;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jdk.net.ExtendedSocketOptions;

public class ClientServerTransfer {
  private static final int DEFAULT_BUFFER_SIZE = 8192;
  private static final Logger logger = Logger.getLogger(ClientServerTransfer.class.getName());

  /**
   * How long a relay may go without bytes flowing in either direction before it is torn down. Same
   * order of magnitude as 3proxy's long-connection timeout; bounds the threads and file descriptors
   * held by clients that open a tunnel and then forget about it.
   */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);

  /**
   * TCP keepalive probing, where the platform supports tuning it: first probe after {@code
   * KEEPALIVE_IDLE_SECONDS} of silence, then every {@code KEEPALIVE_INTERVAL_SECONDS}, giving up
   * after {@code KEEPALIVE_COUNT} unanswered probes. Detects a vanished peer in about 90 s instead
   * of the OS default (usually 2 h before the first probe).
   */
  static final int KEEPALIVE_IDLE_SECONDS = 60;

  static final int KEEPALIVE_INTERVAL_SECONDS = 10;
  static final int KEEPALIVE_COUNT = 3;

  private final Socket client;
  private final Socket server;
  private final Metrics metrics;
  private final Duration idleTimeout;

  /** {@link System#nanoTime()} of the last byte read or written in either direction. */
  private final AtomicLong lastActivity = new AtomicLong();

  /**
   * @param idleTimeout tear the relay down after this long without traffic in either direction;
   *     {@link Duration#ZERO} disables the check
   */
  public ClientServerTransfer(Socket client, Socket server, Metrics metrics, Duration idleTimeout) {
    if (idleTimeout.isNegative()) {
      throw new IllegalArgumentException("Idle timeout must not be negative: " + idleTimeout);
    }
    this.client = client;
    this.server = server;
    this.metrics = metrics;
    this.idleTimeout = idleTimeout;
  }

  public void start() {
    enableKeepAlive(client);
    enableKeepAlive(server);

    lastActivity.set(System.nanoTime());
    CountDownLatch finished = new CountDownLatch(2);

    Thread upload =
        Thread.ofVirtual()
            .name(client + " => " + server)
            .start(() -> transfer(client, server, true, finished));

    Thread download =
        Thread.ofVirtual()
            .name(client + " <= " + server)
            .start(() -> transfer(server, client, false, finished));

    // Block until both directions finish so the caller can treat the connection as active
    // for its whole lifetime (e.g. for accurate active-connection metrics).
    try {
      awaitUntilFinishedOrIdle(finished);
      upload.join();
      download.join();
    } catch (InterruptedException e) {
      // Tear the relay down so the connection doesn't outlive start(), which would leave the
      // caller's active-connection accounting out of sync with open sockets.
      closeQuietly(client);
      closeQuietly(server);
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for both directions to finish, closing both sockets if no traffic flows for {@link
   * #idleTimeout}. This watchdog, rather than a per-socket {@code SO_TIMEOUT}, is what decides
   * idleness: a long one-way download leaves the other direction silent without being idle, and a
   * write blocked on a peer that stopped reading has no read timeout that could ever fire. Closing
   * the sockets unblocks both the reads and the writes.
   */
  private void awaitUntilFinishedOrIdle(CountDownLatch finished) throws InterruptedException {
    if (idleTimeout.isZero()) {
      finished.await();
      return;
    }
    long idleNanos = idleTimeout.toNanos();
    while (true) {
      long remaining = idleNanos - (System.nanoTime() - lastActivity.get());
      if (remaining <= 0) {
        logger.info(
            () ->
                "Closing relay "
                    + client.getRemoteSocketAddress()
                    + " <-> "
                    + server.getRemoteSocketAddress()
                    + ": idle for "
                    + idleTimeout);
        closeQuietly(client);
        closeQuietly(server);
        return;
      }
      if (finished.await(remaining, TimeUnit.NANOSECONDS)) {
        return;
      }
    }
  }

  private void transfer(
      Socket source, Socket destination, boolean isUpload, CountDownLatch finished) {
    try {
      InputStream in = source.getInputStream();
      OutputStream out = destination.getOutputStream();
      byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
      int read;

      while ((read = in.read(buffer, 0, DEFAULT_BUFFER_SIZE)) >= 0) {
        lastActivity.set(System.nanoTime());
        out.write(buffer, 0, read);
        out.flush();
        lastActivity.set(System.nanoTime());
        if (isUpload) {
          metrics.addBytesUploaded(read);
        } else {
          metrics.addBytesDownloaded(read);
        }
      }
    } catch (IOException e) {
      logger.log(Level.FINE, "Transfer ended: " + e.getMessage());
    } finally {
      closeQuietly(client);
      closeQuietly(server);
      finished.countDown();
    }
  }

  /**
   * Turns on TCP keepalive so a peer that disappeared without a FIN/RST (crash, NAT/firewall
   * dropping the mapping, cable pulled) is detected and the relay released, even while the
   * connection is otherwise legitimately silent. Best effort: failures are logged, not fatal.
   */
  static void enableKeepAlive(Socket socket) {
    try {
      socket.setKeepAlive(true);
      setIfSupported(socket, ExtendedSocketOptions.TCP_KEEPIDLE, KEEPALIVE_IDLE_SECONDS);
      setIfSupported(socket, ExtendedSocketOptions.TCP_KEEPINTERVAL, KEEPALIVE_INTERVAL_SECONDS);
      setIfSupported(socket, ExtendedSocketOptions.TCP_KEEPCOUNT, KEEPALIVE_COUNT);
    } catch (IOException | UnsupportedOperationException e) {
      logger.log(Level.FINE, "Could not enable TCP keepalive on " + socket, e);
    }
  }

  private static <T> void setIfSupported(Socket socket, SocketOption<T> option, T value)
      throws IOException {
    if (socket.supportedOptions().contains(option)) {
      socket.setOption(option, value);
    }
  }

  private void closeQuietly(Socket socket) {
    try {
      if (!socket.isClosed()) {
        socket.close();
      }
    } catch (IOException e) {
      logger.log(Level.FINE, "Error closing socket", e);
    }
  }
}
