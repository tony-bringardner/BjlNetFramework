# Changelog

## 1.0.0

First stable release. It contains breaking changes from 0.1.x; see
[Upgrading from 0.1.x](#upgrading-from-01x) below.

Requires Java 11, `bjl_core` 1.0.0 and `bjl_io` 1.0.0 (both have their own breaking changes,
see their changelogs).

### Breaking changes

- **Client TLS certificates are validated.** `Client` used to accept any server certificate.
  It now trusts the JVM trust store plus certificates the user approved through
  `DynamicTrustManager`, and checks that the certificate matches the host name.
  `setTrustAllCertificates(true)` restores the old behavior; `setVerifyHostname(false)` turns
  off only the host name check.
- **Timeouts are on by default.** Server connections time out reads after 60 seconds
  (`Server.setConnectionTimeout`), clients after 5 minutes (`setTimeout`, or the `readTimeout`
  property). Clients also have a 30 second connect timeout. Use 0 for the old wait-forever behavior.
- **Lines are limited to 64 KiB.** A longer line ends the session with a `LineTooLongException`.
  Change it with `Connection.setDefaultMaxLineLength` or `setMaxLineLength`, 0 for no limit.
- **A failing command no longer ends the session.** A `RuntimeException` from a command is
  logged and the client gets a `500` reply; only I/O errors end the session. The exception text
  is no longer sent to the client.
- **`DefaultRequestContext`**: a one character separator is literal (`|` and `.` were regex
  operators), and `getRemainingTokens()` returns the rest of the line exactly as received.
- **`Server.getServerSocketFactory(boolean)` / `getSocketFactory(boolean)`** throw
  `IllegalStateException` with the cause instead of returning `null`.
- **`DynamicTrustManager`** extends `X509ExtendedTrustManager` (it no longer extends
  `BaseObject`) and approvals are tied to the host. Approvals saved by 0.1.x still apply to any host.
- **Permissions are stored by name** in `AbstractPrincipal`, so duplicates are ignored and
  `remove()` really revokes. The package-private `permissions` field is now a `Map`.
- **Server runtime values**: setting `null` removes the value, and `setRuntimeValues` copies
  the map it is given.
- **Removed** `Server.getBufferSize()`, `setBufferSize()` and `DEFAULT_BUFFER_SIZE` (never
  used) and the unused `ISession` interface.

### Deprecated

Misspelled names have correctly spelled replacements. The old names still work in 1.x and
will be removed in 2.0.

| Deprecated | Use |
|---|---|
| `IPrincipal.getPermisssions()` | `getPermissions()` |
| `ICommandResponse.readResonse()` | `readResponse()` |
| `ICommandClient` / `IRequestContext` `getSeperator()`, `setSeperator()` | `getSeparator()`, `setSeparator()` |
| `DefaultRequestContext.getDefaultSeperator()`, `setDefaultSeperator()` | `getDefaultSeparator()`, `setDefaultSeparator()` |
| `Server.getServerGreating()`, `setServerGreating()` | `getServerGreeting()`, `setServerGreeting()` |
| `IServer.AUTHENTICATOION_PROVIDER_PROPERTY` | `AUTHENTICATION_PROVIDER_PROPERTY` |
| `REPLY_300_GENERIC_TEMPOARY_OK`, `REPLY_400_GENERIC_TEMPOARY_ERROR` | `REPLY_300_GENERIC_TEMPORARY_OK`, `REPLY_400_GENERIC_TEMPORARY_ERROR` |
| `Server.DEFAULT_MAX_IDEL_CONNECTION` | `DEFAULT_MAX_IDLE_CONNECTION` |
| `CertificateValidotorDialog` | `CertificateValidatorDialog` |

In 1.x, classes that implement `IPrincipal` or `ICommandResponse` still implement the old
method (`getPermisssions`, `readResonse`); the new one is a default method that calls it.

### Added

- `Server.startAndWait(timeout)` and `getStartupError()`: a server that can't start (port in
  use, bad key store or password, missing factories) is reported instead of only logged.
- `Server.setMaxClients()` and `setServerBusyMessage()` to limit concurrent connections.
- `Server.setConnectionTimeout()`, `setMaxIdleConnection()`, `getLocalPort()` (useful with
  port 0), `setReuseAddress()`, `setBacklog()`, `setTcpNoDelay()`.
- `MultiLineCommandResponse` for FTP / SMTP style `250-` replies.
- `Client.getLastConnectError()`, `setConnectTimeout()`, `setTrustAllCertificates()`,
  `setVerifyHostname()`, `setTcpNoDelay()`, `Client.setDefaultReadTimeout()`, and the
  `readTimeout` / `connectTimeout` properties.
- Hashed passwords for `FileBasedAcl` / `PropertyAuthenticator`: `FileBasedAcl.hashPassword()`
  (or its `main()`) creates a salted PBKDF2 value to put in the password field.
- `IAccessControlList.authenticateUnknownUser()` so logins for unknown users take as long as
  real ones.
- `DynamicTrustManager.removeTrusted(host)` and `CertificateValidator.validate(cert, host)`;
  `CertificateValidatorDialog` shows the host.
- `Connection.setMaxLineLength()` / `setDefaultMaxLineLength()`.
- A javadoc jar is published with the sources jar.

### Fixed

- Thread safety: the server's client list and runtime values, lazily created access control,
  and `DynamicTrustManager`'s approvals are safe to use from many connections at once.
- Sessions that switched to TLS were never removed from the server's client list, and one bad
  entry stopped idle connections from being closed. Idle connections are now also closed when
  the server is quiet.
- The accept thread no longer writes the greeting or runs TLS handshakes, so one slow client
  can't stop new connections; a failed hand off closes the socket; an unexpected error no
  longer stops the server.
- A user who isn't logged in gets "not authorized" instead of a dropped connection.
- `negotiateSecureSocket()` works on the client side (it was hard coded to server mode), checks
  "already secure" before touching the live TLS socket, and only switches the socket factory
  after a successful handshake. A client that reconnects after STARTTLS starts in plain text.
- Connections accepted by a TLS server socket report `isSecure()`, so STARTTLS on them is refused.
- `Server.getSSLContext(String)` ignored the requested protocol.
- `AbstractPrincipal.setParameters()` cleared the caller's map and left the principal unchanged.
- Plain text passwords are compared in constant time, and the user file is read as UTF-8.
- A malformed reply code gives a `500` response instead of `NumberFormatException`.
- The key store file is closed after loading; `DynamicTrustManager` saves its file atomically.

### Upgrading from 0.1.x

1. Change the dependency to `bjl_net_framework` 1.0.0 (it brings `bjl_core` and `bjl_io` 1.0.0).
2. Clients connecting to servers with self-signed certificates: add the certificate to the JVM
   trust store, set a `CertificateValidator` (for example `VisualCertificateValidator`) with
   `DynamicTrustManager.setDefaultValidator()`, or call `setTrustAllCertificates(true)`.
3. If commands legitimately wait longer than the new timeouts, or read lines longer than
   64 KiB, raise the limits (see Breaking changes).
4. Replace deprecated names; your IDE lists them as warnings.
5. Remove calls to `Server.getBufferSize()` / `setBufferSize()` and uses of `ISession`.
