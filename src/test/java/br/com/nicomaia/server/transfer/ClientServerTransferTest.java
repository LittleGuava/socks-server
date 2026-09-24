package br.com.nicomaia.server.transfer;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ClientServerTransferTest {
    @Test
    void relaysBothDirectionsAndPropagatesHalfClose() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket clientListener = new ServerSocket(0, 1, loopback);
             ServerSocket targetListener = new ServerSocket(0, 1, loopback);
             Socket client = new Socket(loopback, clientListener.getLocalPort());
             Socket proxyClient = clientListener.accept();
             Socket target = new Socket(loopback, targetListener.getLocalPort());
             Socket proxyTarget = targetListener.accept();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            client.setSoTimeout(3000);
            target.setSoTimeout(3000);

            Future<?> relay = executor.submit(() -> {
                try {
                    new ClientServerTransfer(proxyClient, proxyTarget).transfer();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            byte[] request = "request".getBytes(StandardCharsets.UTF_8);
            client.getOutputStream().write(request);
            client.shutdownOutput();
            assertArrayEquals(request, target.getInputStream().readAllBytes());

            byte[] response = "response".getBytes(StandardCharsets.UTF_8);
            target.getOutputStream().write(response);
            target.shutdownOutput();
            assertArrayEquals(response, client.getInputStream().readAllBytes());
            relay.get(3, TimeUnit.SECONDS);
        }
    }
}
