package br.com.nicomaia.server.protocol;

import br.com.nicomaia.server.commands.Command;
import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.Address;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import br.com.nicomaia.server.net.ResolverNotFoundException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocksProtocolHandler {

  private static final Logger logger = Logger.getLogger(SocksProtocolHandler.class.getName());

  private static final byte RESERVED = 0x00;

  /**
   * Total time a client gets to send everything it owes during the handshake: greeting,
   * credentials and command request. It is an absolute deadline across all reads, not a per-read
   * timeout. It does not cover resolving the requested domain name or connecting to the
   * destination, which happen on the server side after the command request has been read.
   */
  static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

  private final AddressResolver addressResolver;
  private final HandlersHolder handlers;
  private final Socks5Authenticator authenticator;
  private final HandshakeThrottle throttle;
  private final Duration handshakeTimeout;
  private final Clock clock;

  public SocksProtocolHandler(
      AddressResolver addressResolver,
      HandlersHolder handlers,
      Socks5Authenticator authenticator,
      HandshakeThrottle throttle) {
    this(
        addressResolver,
        handlers,
        authenticator,
        throttle,
        DEFAULT_HANDSHAKE_TIMEOUT,
        Clock.systemUTC());
  }

  SocksProtocolHandler(
      AddressResolver addressResolver,
      HandlersHolder handlers,
      Socks5Authenticator authenticator,
      HandshakeThrottle throttle,
      Duration handshakeTimeout,
      Clock clock) {
    this.addressResolver = addressResolver;
    this.handlers = handlers;
    this.authenticator = authenticator;
    this.throttle = throttle;
    this.handshakeTimeout = handshakeTimeout;
    this.clock = clock;
  }

  public void handle(Socket clientSocket) {
    InetAddress peer = clientSocket.getInetAddress();
    var admission = throttle.tryAcquire(peer);
    if (!admission.isAdmitted()) {
      if (admission == HandshakeThrottle.Admission.CAPACITY_REACHED) {
        // Once per client per window, so operators can diagnose bursts or busy NAT addresses
        // without a flooding host flooding the log.
        logger.warning(
            "Refusing connections from "
                + peer
                + ": too many concurrent handshakes (further refusals in the next "
                + throttle.window()
                + " are logged at FINE)");
      } else {
        logger.fine(() -> "Refusing connection from " + peer + ": " + admission);
      }
      closeQuietly(clientSocket);
      return;
    }

    CommandRequest request;
    try {
      request = performHandshake(clientSocket, peer);
    } finally {
      throttle.release(peer);
    }
    if (request == null) {
      return;
    }

    // Outside the handshake: name resolution has no timeout of its own and must neither eat into
    // the client's deadline nor hold one of its throttle slots.
    Command command = resolve(clientSocket, peer, request);
    if (command != null) {
      relay(clientSocket, command);
    }
  }

  /** A command request as read from the wire, before the destination address is resolved. */
  private record CommandRequest(
      byte socksVersion, CommandType commandType, Address address, int port) {}

  /** @return the client's request, or {@code null} if the handshake failed (socket closed). */
  private CommandRequest performHandshake(Socket clientSocket, InetAddress peer) {
    try {
      InputStream in = new DeadlineInputStream(clientSocket, handshakeTimeout, clock);

      var outcome =
          authenticator.authenticate(in, clientSocket.getOutputStream(), attemptBudget(peer));
      if (!outcome.isAccepted()) {
        // Never log the username: a password pasted into the username field would leak.
        String message = "Rejected SOCKS handshake from " + peer + ": " + outcome;
        switch (outcome) {
          // A failed password check is what fail2ban and operators need to see.
          case INVALID_CREDENTIALS -> logger.warning(message);
          // The block itself was already logged once; repeating it per attempt would flood.
          case THROTTLED -> logger.fine(message);
          // Protocol mismatches (scanners, HTTP/TLS clients on the port) are routine noise.
          default -> logger.info(message);
        }
        closeQuietly(clientSocket);
        return null;
      }

      CommandRequest request = readCommand(peer, in);
      if (request == null) {
        closeQuietly(clientSocket);
      }
      return request;
    } catch (SocketTimeoutException e) {
      logger.info(
          "Closing connection from "
              + peer
              + ": handshake not completed within "
              + handshakeTimeout);
    } catch (EOFException | SocketException e) {
      // Client hung up (FIN or RST) mid-handshake: scanners and impatient clients do this a lot.
      logger.fine(() -> "Client disconnected during handshake: " + peer);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error handling SOCKS handshake", e);
    }
    closeQuietly(clientSocket);
    return null;
  }

  /** Each password comparison spends one of the client's guesses in the throttle window. */
  private Socks5Authenticator.AttemptBudget attemptBudget(InetAddress peer) {
    return new Socks5Authenticator.AttemptBudget() {
      @Override
      public boolean tryReserve() {
        return throttle.tryBeginAttempt(peer);
      }

      @Override
      public void complete(boolean credentialsValid) {
        if (credentialsValid) {
          throttle.recordSuccess(peer);
        } else if (throttle.recordFailure(peer)) {
          logger.warning(
              "Blocking " + peer + " for " + throttle.window() + " after repeated failed logins");
        }
      }
    };
  }

  /** @return the parsed request, or {@code null} if the request header is invalid. */
  private CommandRequest readCommand(InetAddress peer, InputStream in) throws IOException {
    byte[] buffer = SocketReader.readFully(in, 4);

    byte socksVersion = buffer[0];
    if (socksVersion != Socks5Authenticator.SOCKS_VERSION || buffer[2] != RESERVED) {
      logger.info(
          "Rejected SOCKS request from "
              + peer
              + ": invalid VER/RSV "
              + socksVersion
              + "/"
              + buffer[2]);
      return null;
    }

    CommandType commandType = CommandType.valueOf(buffer[1]);
    AddressType addressType = AddressType.valueOf(buffer[3]);

    Address address = SocketReader.readAddress(addressType, in);
    int port = SocketReader.readPort(in);
    return new CommandRequest(socksVersion, commandType, address, port);
  }

  /** @return the command with its destination resolved, or {@code null} (socket closed). */
  private Command resolve(Socket clientSocket, InetAddress peer, CommandRequest request) {
    try {
      Address address = request.address();
      var command =
          new Command(
              request.socksVersion(),
              request.commandType(),
              address.addressType(),
              addressResolver.resolve(address),
              request.port());
      logger.info(command.toString());
      return command;
    } catch (UnknownHostException e) {
      logger.info("Closing connection from " + peer + ": cannot resolve " + e.getMessage());
    } catch (ResolverNotFoundException e) {
      logger.info(
          "Closing connection from "
              + peer
              + ": unsupported address type "
              + request.address().addressType());
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error resolving SOCKS destination", e);
    }
    closeQuietly(clientSocket);
    return null;
  }

  private void relay(Socket clientSocket, Command command) {
    try {
      // Handshake is over: the relay may legitimately sit idle, so lift the handshake timeout.
      clientSocket.setSoTimeout(0);
      handlers.get(command.commandType()).handle(clientSocket, command);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error handling SOCKS connection", e);
      closeQuietly(clientSocket);
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
