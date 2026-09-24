# SOCKS5 server

This project is a Java 21 SOCKS5 proxy supporting the `CONNECT` command and
username/password authentication (RFC 1929).

## Build and run

Provision `nexus_deps_USR` and `nexus_deps_psw` from your secret manager or host
environment before running these commands. The server does not provide a
no-authentication fallback:

```powershell
mvn test
mvn package
java -jar target/server-1.0-SNAPSHOT.jar
```

The optional first argument selects the listening port. The default is `5353`;
port `0` asks the operating system to choose an available port. The server
retains its existing wildcard bind behavior, so it can accept connections on
any interface available to the host. Restrict access with host/network firewall
rules and do not expose it to untrusted networks.

SOCKS5 username/password authentication sends credentials without transport
encryption. Use only on a trusted network or protect the connection with a
secure tunnel. Never put credential values in source files, command history, or
logs; provision them through a secret manager/environment configuration.

The server rejects startup if either `nexus_deps_USR` or `nexus_deps_psw` is
missing or empty. It allows at most 256 concurrent client sessions.
