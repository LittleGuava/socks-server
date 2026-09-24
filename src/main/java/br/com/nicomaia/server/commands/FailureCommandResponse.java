package br.com.nicomaia.server.commands;

public class FailureCommandResponse extends CommandResponse {
    public FailureCommandResponse(Command command) {
        this(command, ResponseType.SOCKS_SERVER_FAILURE);
    }

    public FailureCommandResponse(Command command, ResponseType responseType) {
        super(command, responseType);
    }
}
