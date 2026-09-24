package br.com.nicomaia.server;

import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.handlers.ConnectHandler;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import br.com.nicomaia.server.net.resolvers.DomainInetResolver;
import br.com.nicomaia.server.net.resolvers.InetResolver;
import br.com.nicomaia.server.net.resolvers.IpInetResolver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static final int DEFAULT_PORT = 5353;
    private static final int MAX_CONNECTIONS = 256;

    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        Socks5Credentials credentials = Socks5Credentials.fromEnvironment();

        Map<AddressType, InetResolver> resolvers = new HashMap<>();
        resolvers.put(AddressType.IPV4, new IpInetResolver());
        resolvers.put(AddressType.IPV6, new IpInetResolver());
        resolvers.put(AddressType.DOMAIN_NAME, new DomainInetResolver());
        AddressResolver addressResolver = new AddressResolver(resolvers);

        HandlersHolder handlers = new HandlersHolder();
        handlers.register(CommandType.CONNECT, new ConnectHandler());
        Socks5ConnectionHandler connectionHandler = new Socks5ConnectionHandler(credentials, addressResolver, handlers);
        Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
        Semaphore connectionLimit = new Semaphore(MAX_CONNECTIONS);

        try (ServerSocket serverSocket = new ServerSocket(port);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Thread shutdownHook = new Thread(() -> {
                closeQuietly(serverSocket);
                activeClients.forEach(Main::closeQuietly);
                executor.shutdownNow();
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "socks-server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            LOGGER.info(() -> "SOCKS5 server listening on " + serverSocket.getInetAddress() + ":" + serverSocket.getLocalPort());

            try {
                while (!serverSocket.isClosed()) {
                    Socket clientSocket;
                    try {
                        clientSocket = serverSocket.accept();
                    } catch (SocketException e) {
                        if (serverSocket.isClosed()) {
                            break;
                        }
                        throw e;
                    }

                    if (!connectionLimit.tryAcquire()) {
                        LOGGER.warning("Connection limit reached; rejecting client");
                        closeQuietly(clientSocket);
                        continue;
                    }

                    activeClients.add(clientSocket);
                    try {
                        executor.submit(() -> {
                            try (clientSocket) {
                                connectionHandler.handle(clientSocket);
                            } catch (IOException e) {
                                LOGGER.log(Level.FINE,
                                        "Client connection ended: " + clientSocket.getRemoteSocketAddress(), e);
                            } finally {
                                activeClients.remove(clientSocket);
                                connectionLimit.release();
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        activeClients.remove(clientSocket);
                        connectionLimit.release();
                        closeQuietly(clientSocket);
                        if (!serverSocket.isClosed()) {
                            throw e;
                        }
                    }
                }
            } finally {
                activeClients.forEach(Main::closeQuietly);
            }

            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down.
            }
        }
    }

    private static int parsePort(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: java -jar server.jar [port]");
        }

        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        return port;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to close client socket", e);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to close server socket", e);
        }
    }
}
