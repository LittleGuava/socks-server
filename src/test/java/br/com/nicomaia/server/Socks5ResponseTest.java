package br.com.nicomaia.server;

import br.com.nicomaia.server.commands.ResponseType;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Socks5ResponseTest {
    @Test
    void encodesIpv4BoundAddressAndUnsignedPort() throws Exception {
        byte[] response = Socks5Response.encode(
                5, ResponseType.SUCCEEDED, InetAddress.getByName("127.0.0.1"), 0xFEDC);

        assertEquals(10, response.length);
        assertEquals(5, response[0] & 0xFF);
        assertEquals(0, response[1] & 0xFF);
        assertEquals(1, response[3] & 0xFF);
        assertEquals(0xFE, response[8] & 0xFF);
        assertEquals(0xDC, response[9] & 0xFF);
    }

    @Test
    void encodesIpv6AddressWithCorrectAddressType() throws Exception {
        byte[] response = Socks5Response.encode(
                5, ResponseType.SUCCEEDED, InetAddress.getByName("::1"), 80);

        assertEquals(22, response.length);
        assertEquals(4, response[3] & 0xFF);
        assertEquals(0, response[20] & 0xFF);
        assertEquals(80, response[21] & 0xFF);
    }
}
