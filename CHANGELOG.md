# Changelog

## 1.1.0 (unreleased)

Requires `bjl_core` 1.3.0 and `bjl_io` 1.1.0 (published to GitHub Packages; the pom now names the
repository, so a build no longer needs it in `~/.m2/settings.xml`). Nothing is removed or
incompatible; see Changed for behaviour to be aware of.

### Added

- **Session tasks and a scheduler (BJL-59).** `Server.startTask(session, thread)` starts a thread that
  belongs to a session: it runs on the same kind of thread as sessions, gets a name, keeps the idle
  check from closing its session, and is stopped when the session ends or the server stops
  (`setTaskStopWait()`, default 5 seconds). `schedule()` and `scheduleAtFixedRate()` run work on the
  server's own scheduler thread. `IServer` has default versions for servers that don't manage tasks.
- **Virtual threads for sessions (BJL-51).** `Server.setVirtualThreads(OFF | ON | AUTO)` or the
  `VirtualThreads` property. OFF (the default) is unchanged; ON uses virtual threads on Java 21+;
  AUTO only on Java 24+. Safe on Java 21-23 with `bjl_io` 1.0.1 or later (its line reader uses a lock).
- **Pipelining (BJL-43).** `CommandClient.executeCommands(list)` sends several commands at once (at
  most `MAX_PIPELINED`, 64, per batch) and reads the replies in order, one round trip per batch
  instead of per command (for example SMTP PIPELINING, RFC 2920). `ICommandClient` has a default
  that runs them one at a time.
- **Multi-line replies in one write (BJL-41).** `Connection.writeLines(list)` /
  `IConnection.writeLines` and `AbstractCommandProcessor.reply(list)` send several lines with one
  flush, so a `211-` ... `211 End` reply goes out as one TCP segment or TLS record, not one per line.
- **TLS session resumption for clients (BJL-39).** Clients share one TLS context per protocol and trust
  mode, so a client that reconnects to the same server resumes its session instead of a full
  handshake. `Client.setContext()` / `getContext()` give a group of clients their own context.
- **A limit on concurrent password hashing (BJL-40).** Each PBKDF2 check takes a few hundred
  milliseconds of CPU by design, so a burst of logins could use every core. `FileBasedAcl` runs at most
  `setMaxConcurrentHashes()` at once (default half the cores) and a login waits up to `setHashWaitMs()`
  (default 30 seconds) for its turn (properties `FileBasedAcl.maxConcurrentHashes` and
  `FileBasedAcl.hashWaitMs`).
- **Keep-alive.** Accepted sockets get SO_KEEPALIVE when `bjl_core`'s `KeepAlive` setting is on
  (`setKeepAlive(true)`, default off), so clients that vanish without closing are noticed.

### Fixed

- **STARTTLS / AUTH TLS used a new TLS context for every upgrade (BJL-38).** `getSSLContext(name)`
  switched the server's protocol to the name the client sent, built a context and switched back:
  the server's main context was thrown away each time, no session could be resumed (so FTP data
  connections couldn't resume the control session), and another thread could get the wrong context
  during the switch. Every upgrade now uses the server's one context.
- **Without an access control provider, every command took the server lock** to look the provider
  property up again and logged "No access control defined" (BJL-42). It is now looked up and logged once.
- **TLS sessions ended with a `user_canceled` alert** before `close_notify` (Java's close of a TLS 1.3
  socket), which GnuTLS clients such as FileZilla and lftp report as a fatal error (BJL-2). Only
  `close_notify` is sent now.
- The connection's write lock is a lock, not `synchronized`, so a virtual thread flushing to a slow
  client doesn't hold on to its carrier thread on Java 21-23 (BJL-55).

### Changed (may need a code change)

- `Server.getSSLContext(name)` accepts only TLS or SSL mechanism names (`TLS`, `SSL`, `TLS-C`,
  `TLS-P`, `TLSv1.3` ...); anything else throws `IOException`. The name no longer selects a TLS
  version: use the socket's enabled protocols (for example `SecureBaseObject.PROPERTY_FORCE_TLS_VERSION`).
- `doAdmin()` (the idle connection check) runs on the server's scheduler thread instead of between
  accepts. A subclass that overrides it now runs on that thread.
- Under a burst of logins, a password check can fail after waiting `hashWaitMs` for its turn (the
  client can retry), instead of every login slowing every session.
- `Connection`'s TLS upgrade and `Client.setTrustAllCertificates(true)` use `bjl_core`'s `TlsSockets`
  and `TrustAllCertificates`. Behaviour is unchanged.

## 1.0.1

### Fixed

- `Server.stop()` only set `stopping`, so a server waiting in `accept()` kept running until the accept
  timeout (5 seconds by default). It now closes the listening socket, so it stops at once.
- A stopped `Server` could not be started again: it kept the closed listening socket. A new one is
  created on the next start.

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
