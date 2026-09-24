package br.com.nicomaia.server.net.resolvers;

import br.com.nicomaia.server.net.Address;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public class DomainInetResolver implements InetResolver {
    public InetAddress resolve(Address address) throws UnknownHostException {
        byte[] content = address.content();
        for (byte value : content) {
            if ((value & 0x80) != 0) {
                throw new UnknownHostException("SOCKS5 domain names must use ASCII");
            }
        }
        return InetAddress.getByName(new String(content, StandardCharsets.US_ASCII));
    }
}
