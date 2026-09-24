package br.com.nicomaia.server.protocol;

/**
 * RFC 1929 username/password sub-negotiation request sent by the client after {@code USERNAME}
 * auth method is selected.
 */
public record UsernamePasswordRequest(byte version, byte[] username, byte[] password) {

  public UsernamePasswordRequest {
    username = username.clone();
    password = password.clone();
  }

  @Override
  public byte[] username() {
    return username.clone();
  }

  @Override
  public byte[] password() {
    return password.clone();
  }

  @Override
  public String toString() {
    return "UsernamePasswordRequest{version=" + version + ", credentials=<redacted>}";
  }
}
