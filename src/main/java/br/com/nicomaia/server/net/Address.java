package br.com.nicomaia.server.net;

public record Address(byte[] content, AddressType addressType) {
    public Address {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
