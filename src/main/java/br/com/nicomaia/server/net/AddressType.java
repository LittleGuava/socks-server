package br.com.nicomaia.server.net;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

@Getter
public enum AddressType {
    IPV4((byte) 0x01),
    IPV6((byte) 0x04),
    DOMAIN_NAME((byte) 0x03);

    private final byte typeCode;

    public static Optional<AddressType> fromCode(int typeCode) {
        return Arrays.stream(values())
                .filter(addressType -> (addressType.typeCode & 0xFF) == typeCode)
                .findFirst();
    }

    AddressType(byte typeCode) {
        this.typeCode = typeCode;
    }
}
