package br.com.nicomaia.server.commands;

import lombok.ToString;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;

@ToString
public abstract class CommandResponse {
    private final Command command;
    private final ResponseType responseType;

    public CommandResponse(Command command, ResponseType responseType) {
        this.command = command;
        this.responseType = responseType;
    }

    protected byte[] getBytes(Socket boundSocket) {
        // https://datatracker.ietf.org/doc/html/rfc1928#section-6
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(command.getSocksVersion());
        stream.write(responseType.getNumber());
        stream.write(0x00);

        InetAddress bindAddress = boundSocket == null ? null : boundSocket.getLocalAddress();
        byte[] address = bindAddress == null ? new byte[4] : bindAddress.getAddress();
        stream.write(bindAddress == null || bindAddress instanceof Inet4Address ? 0x01 : 0x04);
        stream.writeBytes(address);

        int port = boundSocket == null ? 0 : boundSocket.getLocalPort();
        stream.write((port >>> 8) & 0xFF);
        stream.write(port & 0xFF);

        return stream.toByteArray();
    }

    public byte[] getBytes() {
        return getBytes(null);
    }
}
