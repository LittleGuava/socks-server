package br.com.nicomaia.server.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientServerTransfer {
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private final Socket client;
    private final Socket server;

    public ClientServerTransfer(Socket client, Socket server) {
        this.client = client;
        this.server = server;
    }

    private static void transferTo(InputStream in, OutputStream out, Socket destination) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        destination.shutdownOutput();
    }

    public void transfer() throws IOException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
            completionService.submit(() -> {
                transferTo(client.getInputStream(), server.getOutputStream(), server);
                return null;
            });
            completionService.submit(() -> {
                transferTo(server.getInputStream(), client.getOutputStream(), client);
                return null;
            });

            try {
                completionService.take().get();
                completionService.take().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IOException failure = new IOException("Interrupted while relaying SOCKS5 traffic", e);
                closeBoth(failure);
                throw failure;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                IOException failure = cause instanceof IOException ioException
                        ? ioException
                        : new IOException("SOCKS5 traffic relay failed", cause);
                closeBoth(failure);
                throw failure;
            }
        }
    }

    private void closeBoth(IOException failure) {
        try {
            client.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        try {
            server.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
