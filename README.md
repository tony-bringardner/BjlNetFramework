# BjlNetFramework

A small Java framework for writing both ends of line based network protocols such as
FTP, SMTP or POP3: a client sends a command line, the server answers with a reply code
and text.

The framework handles the sockets, threads, TLS (from the first byte or with STARTTLS),
authentication, time-outs and limits. You write the commands.

- Java 11 or later
- Depends on [`bjl_core`](https://github.com/tony-bringardner/BjlCore) and
  [`bjl_io`](https://github.com/tony-bringardner/BjlIo)
- Apache License 2.0

## Installation

The artifact is published to GitHub Packages:

```xml
<dependency>
    <groupId>us.bringardner</groupId>
    <artifactId>bjl_net_framework</artifactId>
    <version>1.0.0</version>
</dependency>
```

GitHub Packages needs authentication even for public packages. Add the repositories
(this project and its two dependencies) to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/tony-bringardner/BjlNetFramework</url>
    </repository>
    <repository>
        <id>github-core</id>
        <url>https://maven.pkg.github.com/tony-bringardner/BjlCore</url>
    </repository>
    <repository>
        <id>github-io</id>
        <url>https://maven.pkg.github.com/tony-bringardner/BjlIo</url>
    </repository>
</repositories>
```

Then add a matching `<server>` for each id to `~/.m2/settings.xml`, with your GitHub user name
and a token that has the `read:packages` scope.

## How it fits together

| Server side | |
|---|---|
| `Server` | Accepts connections and starts one processor thread per client. |
| `IConnectionFactory` → `Connection` | Wraps each socket; reads and writes lines (CR LF or LF). |
| `IProcessorFactory` → `AbstractCommandProcessor` | Reads a line, finds the command, checks permission, runs it. |
| `ICommandFactory` → `ICommand` | Your protocol: one `ICommand` per command. |
| `IAccessControlList` (`FileBasedAcl`, `PropertyAuthenticator`) | Users, passwords and permissions. |

| Client side | |
|---|---|
| `CommandClient` | Connects, sends a command, reads the reply. |
| `SingleCommandResponse` / `MultiLineCommandResponse` | Parse `250 text` or `250-...` / `250 last` replies. |
| `DynamicTrustManager` | Decides which server certificates to trust. |

## A minimal echo server and client

```java
ICommand echo = new ICommand() {
    public String getName()            { return "ECHO"; }
    public IPermission getPermission() { return new Permission("ECHO"); }
    public boolean requiresAuthorization() { return false; }
    public void execute(ICommandProcessor processor, IRequestContext context) throws IOException {
        processor.reply(REPLY_200_GENERIC_OK, context.getRemainingTokens());
    }
};

Server server = new Server(2525, "EchoServer");
server.setServerGreeting("220 Echo server ready");
server.setConnectionFactory(socket -> new Connection(socket, true) {   // true = CR LF lines
    @Override
    public SSLContext getSSLContext(String protocol) throws IOException {
        return null;   // no STARTTLS in this example
    }
});
server.setProcessorFactory(() -> new AbstractCommandProcessor() {
    @Override
    public String translateResponseCode(int code) {
        return String.valueOf(code);
    }

    @Override
    public ICommandFactory getCommandFactory() {
        return context -> "ECHO".equalsIgnoreCase(context.getNextToken()) ? echo : null;
    }
});
server.startAndWait(5000);   // throws if the server can't start (port in use, bad key store...)
```

```java
try (CommandClient client = new CommandClient("localhost", 2525)) {
    if (!client.connect()) {
        throw client.getLastConnectError();
    }
    System.out.println(client.readLine());               // the greeting
    ICommandResponse reply = client.executeCommand("ECHO", "hello", "world");
    if (reply.isPositive()) {
        System.out.println(reply.getResponseText());     // hello world
    }
}
```

`TestNetFramework` and `TestNetFrameworkWithPropertyAuth` in `src/test` are complete,
runnable versions of this, the second with a login command and permissions.

Unknown commands get a `500` reply, and so does a command that throws a `RuntimeException`;
the session carries on. Only an I/O error ends a session.

## TLS

**TLS from the first byte.** Give the server a key store and call `setSecure(true)`:

```java
server.setSecure(true);
server.setKeyStoreFileName("/server.p12");   // class path resource or file
server.setKeyStoreType("PKCS12");
server.setKeyStorePassword(password);
```

(or the `KeyStoreName`, `KeyStoreType` and `KeyStorePassword` properties). The client
connects with a TLS socket factory: `client.setSocketFactory(sslContext.getSocketFactory())`.

**STARTTLS.** Return a server `SSLContext` from your `Connection.getSSLContext()`, and write
a command that replies and then calls `processor.getConnection().negotiateSecureSocket("TLS")`.
The client sends that command, then calls `client.negotiateSecureSocket("TLS")`.
A connection that is already secure refuses a second negotiation.

**Which server certificates the client trusts.** By default: the JVM trust store, and the
certificate must match the host name. For self-signed certificates either

- set a `CertificateValidator` with `DynamicTrustManager.setDefaultValidator(...)`. It is asked
  about unknown certificates and can accept once or always. `VisualCertificateValidator` asks the
  user in a dialog. Approvals are stored in `~/.bjlTructed` and apply only to that host;
- or give the client your own trust managers with `client.setContext(...)`;
- or, for testing only, `client.setTrustAllCertificates(true)`.

`client.setVerifyHostname(false)` turns off only the host name check.

## Authentication and permissions

Set an `IAccessControlList` with `server.setAccessControl(...)`, or name the class in the
`AuthenticationProvider` property (`IServer.AUTHENTICATION_PROVIDER_PROPERTY`).
`FileBasedAcl` reads users from the file named by the `<server name>.userFile` property;
`PropertyAuthenticator` reads them from `<server name>.user0`, `.user1`, ... properties.
Each user is one line:

```
# name,  password,         permissions,   parameters
alice,   {PBKDF2}210000:..., ECHO|LOGIN,  home=/home/alice
```

Store hashed passwords rather than plain text. Create the hash with

```
java -cp bjl_net_framework-1.0.0.jar:bjl_core-1.0.0.jar us.bringardner.net.framework.server.FileBasedAcl 'the password'
```

A login command calls `processor.getServer().authenticate(user, password)` and
`processor.setPrincipal(...)`. Commands whose `requiresAuthorization()` is true then run only
if the principal has the command's permission; otherwise the client gets "not authorized".

## Configuration

| Setting | Default | Where |
|---|---|---|
| Server read time-out | 60 s | `Server.setConnectionTimeout(ms)`, 0 = none |
| Idle connections closed after | 24 h | `Server.setMaxIdleConnection(ms)` |
| Maximum concurrent clients | unlimited | `Server.setMaxClients(n)`, `setServerBusyMessage("421 ...")` |
| Longest line | 64 KiB | `Connection.setDefaultMaxLineLength(n)` or `setMaxLineLength(n)`, 0 = none |
| Client read time-out | 5 min | `client.setTimeout(ms)`, `Client.setDefaultReadTimeout(ms)`, or the `readTimeout` property |
| Client connect time-out | 30 s | `client.setConnectTimeout(ms)` or the `connectTimeout` property |
| TCP_NODELAY | on | `setTcpNoDelay(false)` on `Server` or `Client` |
| SO_REUSEADDR | on | `Server.setReuseAddress(false)` |

Properties are looked up with `BaseObject.getProperty` from `bjl_core`: first
`<fully qualified class name>.<name>`, then `<name>`, as system properties or in class-named
`.properties` files on the class path.

## Building

```
mvn test      # unit and socket tests; coverage report in target/site/jacoco/index.html
mvn package   # jar, sources jar and javadoc jar
```

See [CHANGELOG.md](CHANGELOG.md) for changes, including upgrading from 0.1.x.
