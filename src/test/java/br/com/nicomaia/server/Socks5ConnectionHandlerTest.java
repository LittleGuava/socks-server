package br.com.nicomaia.server;

import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.handlers.ConnectHandler;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import br.com.nicomaia.server.net.resolvers.DomainInetResolver;
import br.com.nicomaia.server.net.resolvers.InetResolver;
import br.com.nicomaia.server.net.resolvers.IpInetResolver;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Socks5ConnectionHandlerTest {
    private static final byte[] USERNAME = "proxy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] PASSWORD = "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void rejectsClientsThatDoNotOfferUsernamePassword() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket serverSide = listener.accept();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> handling = executor.submit(() -> handle(serverSide));
            DataInputStream input = new DataInputStream(client.getInputStream());

            client.getOutputStream().write(new byte[]{5, 1, 0});
            assertEquals(5, input.readUnsignedByte());
            assertEquals(0xFF, input.readUnsignedByte());
            handling.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsInvalidCredentialsAndUnsupportedCommands() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket serverSide = listener.accept();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> handling = executor.submit(() -> handle(serverSide));
            DataInputStream input = new DataInputStream(client.getInputStream());
            client.getOutputStream().write(new byte[]{5, 1, 2});
            assertEquals(5, input.readUnsignedByte());
            assertEquals(2, input.readUnsignedByte());

            writeAuthentication(client, "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8), PASSWORD);
            assertEquals(1, input.readUnsignedByte());
            assertEquals(1, input.readUnsignedByte());
            handling.get(2, TimeUnit.SECONDS);
        }

        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket serverSide = listener.accept();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> handling = executor.submit(() -> handle(serverSide));
            DataInputStream input = new DataInputStream(client.getInputStream());
            client.getOutputStream().write(new byte[]{5, 1, 2});
            assertEquals(5, input.readUnsignedByte());
            assertEquals(2, input.readUnsignedByte());
            writeAuthentication(client, USERNAME, PASSWORD);
            assertEquals(1, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            client.getOutputStream().write(new byte[]{5, 0x7F, 0, 1});

            assertEquals(5, input.readUnsignedByte());
            assertEquals(7, input.readUnsignedByte());
            handling.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void authenticatesConnectsAndRelaysThroughTheProxy() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket targetListener = new ServerSocket(0, 1, loopback);
             ServerSocket clientListener = new ServerSocket(0);
             Socket client = new Socket(loopback, clientListener.getLocalPort());
             Socket serverSide = clientListener.accept();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            client.setSoTimeout(4000);
            Future<?> targetTask = executor.submit(() -> {
                try (Socket target = targetListener.accept()) {
                    byte[] received = target.getInputStream().readAllBytes();
                    target.getOutputStream().write(received);
                    target.shutdownOutput();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> handling = executor.submit(() -> handle(serverSide, true));
            DataInputStream input = new DataInputStream(client.getInputStream());
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            output.write(new byte[]{5, 1, 2});
            assertEquals(5, input.readUnsignedByte());
            assertEquals(2, input.readUnsignedByte());
            writeAuthentication(client, USERNAME, PASSWORD);
            assertEquals(1, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());

            InetAddress targetAddress = targetListener.getInetAddress();
            output.writeByte(5);
            output.writeByte(1);
            output.writeByte(0);
            output.writeByte(targetAddress instanceof Inet4Address ? 1 : 4);
            output.write(targetAddress.getAddress());
            output.writeShort(targetListener.getLocalPort());

            assertEquals(5, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            int boundAddressType = input.readUnsignedByte();
            int boundAddressLength = switch (boundAddressType) {
                case 1 -> 4;
                case 4 -> 16;
                default -> throw new AssertionError("Unexpected BND.ADDR type: " + boundAddressType);
            };
            byte[] boundAddress = new byte[boundAddressLength];
            input.readFully(boundAddress);
            assertEquals(targetAddress instanceof Inet4Address ? 1 : 4, boundAddressType);
            assertArrayEquals(targetAddress.getAddress(), boundAddress);
            assertTrue(input.readUnsignedShort() > 0);

            byte[] payload = "through proxy".getBytes(StandardCharsets.UTF_8);
            output.write(payload);
            client.shutdownOutput();
            assertArrayEquals(payload, input.readAllBytes());
            handling.get(4, TimeUnit.SECONDS);
            targetTask.get(4, TimeUnit.SECONDS);
        }
    }

    private static void handle(Socket socket) {
        handle(socket, false);
    }

    private static void handle(Socket socket, boolean connectSupported) {
        try {
            HandlersHolder handlers = new HandlersHolder();
            if (connectSupported) {
                handlers.register(CommandType.CONNECT, new ConnectHandler());
            }
            Socks5ConnectionHandler handler = new Socks5ConnectionHandler(
                    new Socks5Credentials("proxy", "secret"), addressResolver(), handlers);
            handler.handle(socket);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static AddressResolver addressResolver() {
        Map<AddressType, InetResolver> resolvers = new HashMap<>();
        resolvers.put(AddressType.IPV4, new IpInetResolver());
        resolvers.put(AddressType.IPV6, new IpInetResolver());
        resolvers.put(AddressType.DOMAIN_NAME, new DomainInetResolver());
        return new AddressResolver(resolvers);
    }

    private static void writeAuthentication(Socket client, byte[] username, byte[] password) throws IOException {
        client.getOutputStream().write(new byte[]{1, (byte) username.length});
        client.getOutputStream().write(username);
        client.getOutputStream().write((byte) password.length);
        client.getOutputStream().write(password);
    }
}
