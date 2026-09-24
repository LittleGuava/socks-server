package br.com.nicomaia.server.protocol;

/** RFC 1929 username/password sub-negotiation response sent back to the client. */
public record UsernamePasswordResponse(byte version, boolean success) {

  private static final byte STATUS_SUCCESS = 0x00;
  private static final byte STATUS_FAILURE = 0x01;

  public byte[] toBytes() {
    return new byte[] {version, success ? STATUS_SUCCESS : STATUS_FAILURE};
  }
}
