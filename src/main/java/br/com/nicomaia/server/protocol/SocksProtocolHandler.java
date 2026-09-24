package br.com.nicomaia.server.protocol;

import br.com.nicomaia.server.auth.Socks5Credentials;
import br.com.nicomaia.server.commands.Command;
import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.Address;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import br.com.nicomaia.server.net.ResolverNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocksProtocolHandler {

  private static final Logger logger = Logger.getLogger(SocksProtocolHandler.class.getName());

  private final AddressResolver addressResolver;
  private final HandlersHolder handlers;
  private final Socks5Authenticator authenticator;

  public SocksProtocolHandler(
      AddressResolver addressResolver, HandlersHolder handlers, Socks5Credentials credentials) {
    this.addressResolver = addressResolver;
    this.handlers = handlers;
    this.authenticator = new Socks5Authenticator(credentials);
  }

  public void handle(Socket clientSocket) {
    try {
      InputStream in = clientSocket.getInputStream();

      if (!authenticator.authenticate(in, clientSocket.getOutputStream())) {
        closeQuietly(clientSocket);
        return;
      }

      dispatchCommand(clientSocket, in);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error handling SOCKS connection", e);
      closeQuietly(clientSocket);
    }
  }

  private void dispatchCommand(Socket clientSocket, InputStream in)
      throws IOException, ResolverNotFoundException {
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
