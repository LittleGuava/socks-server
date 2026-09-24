package br.com.nicomaia.server;

import br.com.nicomaia.server.commands.ResponseType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;

final class Socks5Response {
    private static final byte[] UNSPECIFIED_ADDRESS = new byte[4];

    private Socks5Response() {
    }

    static void write(OutputStream output, int version, ResponseType responseType) throws IOException {
        output.write(encode(version, responseType, null, 0));
        output.flush();
    }

    static byte[] encode(int version, ResponseType responseType, InetAddress bindAddress, int bindPort) {
        byte[] address = bindAddress == null ? UNSPECIFIED_ADDRESS : bindAddress.getAddress();
        int addressType = bindAddress == null || bindAddress instanceof Inet4Address ? 1 : 4;
        byte[] response = new byte[6 + address.length];
        response[0] = (byte) version;
        response[1] = responseType.getNumber();
        response[2] = 0;
        response[3] = (byte) addressType;
        System.arraycopy(address, 0, response, 4, address.length);
        response[response.length - 2] = (byte) (bindPort >>> 8);
        response[response.length - 1] = (byte) bindPort;
        return response;
    }
}
