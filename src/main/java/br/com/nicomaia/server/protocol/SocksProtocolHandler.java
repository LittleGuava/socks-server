package br.com.nicomaia.server.protocol;

import br.com.nicomaia.server.auth.Socks5Credentials;
import br.com.nicomaia.server.commands.Command;
import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.Address;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocksProtocolHandler {

  private static final Logger logger = Logger.getLogger(SocksProtocolHandler.class.getName());

  private final AddressResolver addressResolver;
  private final HandlersHolder handlers;
  private final Socks5Credentials credentials;

  public SocksProtocolHandler(
      AddressResolver addressResolver, HandlersHolder handlers, Socks5Credentials credentials) {
    this.addressResolver = addressResolver;
    this.handlers = handlers;
    this.credentials = credentials;
  }

  public void handle(Socket clientSocket) {
    try {
      InputStream in = clientSocket.getInputStream();
      OutputStream out = clientSocket.getOutputStream();

      if (!authenticate(in, out)) {
        closeQuietly(clientSocket);
        return;
      }

      // --- Command ---
      byte[] buffer = SocketReader.readFully(in, 4);

      byte socksVersion = buffer[0];
      CommandType commandType = CommandType.valueOf(buffer[1]);
      AddressType addressType = AddressType.valueOf(buffer[3]);

      Address address = SocketReader.readAddress(addressType, in);
      InetAddress inetAddress = addressResolver.resolve(address);
      int port = SocketReader.readPort(in);

      var command = new Command(socksVersion, commandType, addressType, inetAddress, port);
      logger.info(command.toString());

      handlers.get(commandType).handle(clientSocket, command);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error handling SOCKS connection", e);
      closeQuietly(clientSocket);
    }
  }

  /**
   * Performs the SOCKS5 method negotiation followed by the RFC 1929 username/password
   * sub-negotiation. Only clients offering the {@code USERNAME} method are accepted; every other
   * negotiation (including plain {@code NO_AUTH}) is rejected with {@code NO_ACCEPTABLE_METHODS}.
   *
   * @return {@code true} if the client authenticated successfully.
   */
  private boolean authenticate(InputStream in, OutputStream out) throws IOException {
    byte[] header = SocketReader.readFully(in, 2);
    byte socksVersion = header[0];
    int methodCount = header[1] & 0xFF;

    byte[] methodBytes = SocketReader.readFully(in, methodCount);
    Set<SupportedAuthType> offeredMethods = SupportedAuthType.valueOf(methodBytes);

    var authRequest = new AuthRequest(socksVersion, header[1], offeredMethods);
    logger.info(authRequest.toString());

    if (!offeredMethods.contains(SupportedAuthType.USERNAME)) {
      var rejection = new AuthResponse(socksVersion, SupportedAuthType.NO_ACCEPTABLE_METHODS);
      logger.warning("Client did not offer username/password authentication; rejecting");
      out.write(rejection.toBytes());
      out.flush();
      return false;
    }

    var authResponse = new AuthResponse(socksVersion, SupportedAuthType.USERNAME);
    logger.info(authResponse.toString());
    out.write(authResponse.toBytes());
    out.flush();

    var credentialsRequest = SocketReader.readUsernamePassword(in);
    boolean valid =
        credentials.matches(credentialsRequest.username(), credentialsRequest.password());

    out.write(new UsernamePasswordResponse(credentialsRequest.version(), valid).toBytes());
    out.flush();

    if (!valid) {
      logger.warning("Rejected connection: invalid username/password");
    }

    return valid;
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
