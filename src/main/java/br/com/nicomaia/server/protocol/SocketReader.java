package br.com.nicomaia.server.protocol;

import br.com.nicomaia.server.net.Address;
import br.com.nicomaia.server.net.AddressType;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class SocketReader {

  public static Address readAddress(AddressType addressType, InputStream in) throws IOException {
    int length;

    if (AddressType.IPV6 == addressType) {
      length = 16;
    } else if (AddressType.IPV4 == addressType) {
      length = 4;
    } else if (AddressType.DOMAIN_NAME == addressType) {
      length = readDomainLength(in);
    } else {
      throw new IOException("Unsupported address type: " + addressType);
    }

    return new Address(readFully(in, length), addressType);
  }

  public static int readPort(InputStream in) throws IOException {
    byte[] buffer = readFully(in, 2);
    return ((buffer[0] & 0xFF) << 8) | (buffer[1] & 0xFF);
  }

  /** Parses the RFC 1929 username/password sub-negotiation request. */
  public static UsernamePasswordRequest readUsernamePassword(InputStream in) throws IOException {
    byte version = readFully(in, 1)[0];
    int usernameLength = readFully(in, 1)[0] & 0xFF;
    byte[] username = readFully(in, usernameLength);
    int passwordLength = readFully(in, 1)[0] & 0xFF;
    byte[] password = readFully(in, passwordLength);

    return new UsernamePasswordRequest(version, username, password);
  }

  /**
   * Reads exactly {@code length} bytes, looping until the buffer is full since a single {@link
   * InputStream#read(byte[])} call may return fewer bytes than requested on a TCP socket.
   */
  public static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] buffer = new byte[length];
    int totalRead = 0;

    while (totalRead < length) {
      int read = in.read(buffer, totalRead, length - totalRead);
      if (read == -1) {
        throw new EOFException(
            "Unexpected end of stream: expected " + length + " bytes, got " + totalRead);
      }
      totalRead += read;
    }

    return buffer;
  }

  private static int readDomainLength(InputStream in) throws IOException {
    return readFully(in, 1)[0] & 0xFF;
  }
}
