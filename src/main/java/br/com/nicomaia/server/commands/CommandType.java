package br.com.nicomaia.server.commands;

import java.util.Arrays;
import java.util.Optional;

public enum CommandType {
    BIND((byte) 0x02),
    CONNECT((byte) 0x01),
    UDP_ASSOCIATE((byte) 0x03);

    private final byte number;

    CommandType(byte number) {
        this.number = number;
    }

    public byte getNumber() {
        return number;
    }

    public static Optional<CommandType> fromCode(int number) {
        return Arrays.stream(values())
                .filter(commandType -> (commandType.number & 0xFF) == number)
                .findFirst();
    }
}
