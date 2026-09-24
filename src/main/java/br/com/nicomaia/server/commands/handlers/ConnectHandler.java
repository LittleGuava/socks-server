package br.com.nicomaia.server.commands.handlers;

import br.com.nicomaia.server.transfer.ClientServerTransfer;
import br.com.nicomaia.server.commands.Command;
import br.com.nicomaia.server.commands.CommandResponse;
import br.com.nicomaia.server.commands.FailureCommandResponse;
import br.com.nicomaia.server.commands.ResponseType;
import br.com.nicomaia.server.commands.SuccessCommandResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConnectHandler implements CommandHandler {
    private static final Logger LOGGER = Logger.getLogger(ConnectHandler.class.getName());
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    @Override
    public void handle(Socket client, Command command) throws IOException {
        try (Socket proxiedConnection = new Socket()) {
            try {
                proxiedConnection.connect(
                        new InetSocketAddress(command.getAddress(), command.getPort()), CONNECT_TIMEOUT_MILLIS);
            } catch (IOException e) {
                sendResponse(client, new FailureCommandResponse(command, responseFor(e)));
                LOGGER.log(Level.FINE, "Outbound SOCKS5 connection failed", e);
                return;
            }

            sendResponse(client, new SuccessCommandResponse(command, proxiedConnection));
            new ClientServerTransfer(client, proxiedConnection).transfer();
        }
    }

    private static ResponseType responseFor(IOException exception) {
        if (exception instanceof ConnectException) {
            return ResponseType.CONNECTION_REFUSED;
        }
        if (exception instanceof NoRouteToHostException) {
            return ResponseType.NETWORK_UNREACHABLE;
        }
        if (exception instanceof SocketTimeoutException) {
            return ResponseType.TTL_EXPIRED;
        }
        return ResponseType.HOST_UNREACHABLE;
    }

    private void sendResponse(Socket client, CommandResponse response) throws IOException {
        client.getOutputStream().write(response.getBytes());
        client.getOutputStream().flush();
    }
}
