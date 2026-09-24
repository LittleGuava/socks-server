package br.com.nicomaia.server;

import br.com.nicomaia.server.commands.Command;
import br.com.nicomaia.server.commands.CommandType;
import br.com.nicomaia.server.commands.ResponseType;
import br.com.nicomaia.server.commands.handlers.CommandHandler;
import br.com.nicomaia.server.commands.handlers.HandlersHolder;
import br.com.nicomaia.server.net.Address;
import br.com.nicomaia.server.net.AddressResolver;
import br.com.nicomaia.server.net.AddressType;
import br.com.nicomaia.server.net.ResolverNotFoundException;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Optional;

final class Socks5ConnectionHandler {
    private static final int SOCKS_VERSION = 5;
    private static final int USERNAME_PASSWORD_METHOD = 2;
    private static final int AUTH_VERSION = 1;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 30_000;
    private final Socks5Credentials credentials;
    private final AddressResolver addressResolver;
    private final HandlersHolder handlers;

    Socks5ConnectionHandler(Socks5Credentials credentials, AddressResolver addressResolver, HandlersHolder handlers) {
        this.credentials = credentials;
        this.addressResolver = addressResolver;
        this.handlers = handlers;
    }

    void handle(Socket client) throws IOException {
        client.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        DataInputStream input = new DataInputStream(client.getInputStream());
        OutputStream output = client.getOutputStream();
        if (!negotiateAuthentication(input, output) || !authenticate(input, output)) {
            return;
        }

        int version = input.readUnsignedByte();
        int commandCode = input.readUnsignedByte();
        int reserved = input.readUnsignedByte();
        int addressTypeCode = input.readUnsignedByte();

        if (version != SOCKS_VERSION || reserved != 0) {
            Socks5Response.write(output, SOCKS_VERSION, ResponseType.SOCKS_SERVER_FAILURE);
            return;
        }

        Optional<CommandType> commandType = CommandType.fromCode(commandCode);
        if (commandType.isEmpty()) {
            Socks5Response.write(output, version, ResponseType.COMMAND_NOT_SUPPORTED);
            return;
        }

        CommandHandler handler = handlers.get(commandType.get());
        if (handler == null) {
            Socks5Response.write(output, version, ResponseType.COMMAND_NOT_SUPPORTED);
            return;
        }

        Optional<AddressType> addressType = AddressType.fromCode(addressTypeCode);
        if (addressType.isEmpty()) {
            Socks5Response.write(output, version, ResponseType.ADDRESS_TYPE_NOT_SUPPORTED);
            return;
        }

        Address address = readAddress(input, addressType.get());
        int port = input.readUnsignedShort();
        InetAddress destination;
        try {
            destination = addressResolver.resolve(address);
        } catch (UnknownHostException e) {
            Socks5Response.write(output, version, ResponseType.HOST_UNREACHABLE);
            return;
        } catch (ResolverNotFoundException e) {
            Socks5Response.write(output, version, ResponseType.ADDRESS_TYPE_NOT_SUPPORTED);
            return;
        }

        Command command = new Command((byte) version, commandType.get(), addressType.get(), destination, port);
        client.setSoTimeout(0);
        handler.handle(client, command);
    }

    private boolean negotiateAuthentication(DataInputStream input, OutputStream output) throws IOException {
        int version = input.readUnsignedByte();
        int methodCount = input.readUnsignedByte();
        byte[] methods = new byte[methodCount];
        input.readFully(methods);

        boolean usernamePasswordOffered = false;
        for (byte method : methods) {
            usernamePasswordOffered |= (method & 0xFF) == USERNAME_PASSWORD_METHOD;
        }

        if (version != SOCKS_VERSION || !usernamePasswordOffered) {
            output.write(new byte[]{SOCKS_VERSION, (byte) 0xFF});
            output.flush();
            return false;
        }

        output.write(new byte[]{SOCKS_VERSION, USERNAME_PASSWORD_METHOD});
        output.flush();
        return true;
    }

    private boolean authenticate(DataInputStream input, OutputStream output) throws IOException {
        int version = input.readUnsignedByte();
        int usernameLength = input.readUnsignedByte();
        byte[] username = new byte[usernameLength];
        input.readFully(username);
        int passwordLength = input.readUnsignedByte();
        byte[] password = new byte[passwordLength];
        input.readFully(password);

        boolean valid = version == AUTH_VERSION && usernameLength > 0 && passwordLength > 0
                && credentials.matches(username, password);
        output.write(new byte[]{AUTH_VERSION, (byte) (valid ? 0 : 1)});
        output.flush();
        return valid;
    }

    private static Address readAddress(DataInputStream input, AddressType addressType) throws IOException {
        int length = switch (addressType) {
            case IPV4 -> 4;
            case IPV6 -> 16;
            case DOMAIN_NAME -> input.readUnsignedByte();
        };
        if (length == 0) {
            throw new IOException("SOCKS5 domain name must not be empty");
        }
        byte[] content = new byte[length];
        input.readFully(content);
        return new Address(content, addressType);
    }
}
