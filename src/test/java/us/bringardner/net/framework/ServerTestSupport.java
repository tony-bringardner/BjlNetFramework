package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.server.AbstractCommandProcessor;
import us.bringardner.net.framework.server.FileBasedAcl;
import us.bringardner.net.framework.server.IAccessControlList;
import us.bringardner.net.framework.server.ICommand;
import us.bringardner.net.framework.server.ICommandFactory;
import us.bringardner.net.framework.server.ICommandProcessor;
import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IPrincipal;
import us.bringardner.net.framework.server.IRequestContext;
import us.bringardner.net.framework.server.Permission;
import us.bringardner.net.framework.server.Server;

/**
 * Shared helpers for tests that need a running server, TLS material or to wait for something.
 * Not a test itself.
 */
public class ServerTestSupport {

	/** Self-signed certificate for CN=localhost (SAN dns:localhost only), valid until 2126. */
	public static final String KEYSTORE = "/test-keystore.p12";
	public static final String KEY_ALIAS = "test";
	public static final char[] PASSWORD = "changeit".toCharArray();

	public static final String ECHO = "Echo";
	public static final String FAIL = "Fail";
	public static final String SECRET = "Secret";
	public static final String LOGIN = "Login";
	public static final String START_TLS = "StartTls";
	public static final String FAIL_DETAIL = "internal detail that must not reach the client";

	/**
	 * A Server that lets tests run the idle connection check on demand.
	 */
	public static class TestServer extends Server {
		public TestServer(String name) {
			super(0, name);
		}

		public void runAdmin() {
			doAdmin();
		}
	}

	public interface CommandBody {
		void execute(ICommandProcessor processor, IRequestContext context) throws IOException;
	}

	public static ICommand command(String name, boolean requiresAuthorization, CommandBody body) {
		return new ICommand() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public IPermission getPermission() {
				return new Permission(name);
			}

			@Override
			public boolean requiresAuthorization() {
				return requiresAuthorization;
			}

			@Override
			public void execute(ICommandProcessor processor, IRequestContext context) throws IOException {
				body.execute(processor, context);
			}
		};
	}

	/**
	 * Echo (no auth), Fail (throws), Secret (needs the Secret permission), Login user password, StartTls.
	 */
	public static Map<String, ICommand> standardCommands() {
		Map<String, ICommand> ret = new HashMap<>();
		ret.put(ECHO, command(ECHO, false, (p, c) -> p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK, c.getRemainingTokens())));
		ret.put(FAIL, command(FAIL, false, (p, c) -> {
			throw new IllegalStateException(FAIL_DETAIL);
		}));
		ret.put(SECRET, command(SECRET, true, (p, c) -> p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK, "the secret")));
		ret.put(LOGIN, command(LOGIN, false, (p, c) -> {
			String user = c.getNextToken();
			String password = c.getNextToken();
			if( user == null || password == null ) {
				p.reply(IGenericResponseCode.REPLY_500_GENERIC_ERROR, "Not enough parameters");
				return;
			}
			IPrincipal principal = p.getServer().authenticate(user, password.getBytes());
			p.setPrincipal(principal);
			if( principal == null ) {
				p.reply(IGenericResponseCode.REPLY_400_GENERIC_TEMPORARY_ERROR, "User not identified");
			} else {
				p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK, "Ok");
			}
		}));
		ret.put(START_TLS, command(START_TLS, false, (p, c) -> {
			p.reply(220, "Ready to start TLS");
			p.getConnection().negotiateSecureSocket("TLS");
		}));
		return ret;
	}

	/**
	 * Create (not start) a server on a free port.
	 * @param tls server side TLS context for StartTls, or null
	 */
	public static TestServer create(String name, Map<String, ICommand> commands, SSLContext tls) {
		TestServer svr = new TestServer(name);
		// Short accept timeout so stop() is quick
		svr.setAcceptTimeout(200);
		svr.setConnectionFactory(socket -> new Connection(socket, true) {
			@Override
			public SSLContext getSSLContext(String sslOrTsl) throws IOException {
				if( tls == null ) {
					throw new IOException("TLS is not configured for this test server");
				}
				return tls;
			}
		});
		svr.setProcessorFactory(() -> new AbstractCommandProcessor() {
			private static final long serialVersionUID = 1L;

			@Override
			public String translateResponseCode(int code) {
				return "" + code;
			}

			@Override
			public ICommandFactory getCommandFactory() {
				return context -> {
					String name1 = context.getNextToken();
					return name1 == null ? null : commands.get(name1);
				};
			}
		});
		return svr;
	}

	public static TestServer start(TestServer svr) {
		svr.start();
		waitFor(svr::isRunning, 5000, "server did not start");
		return svr;
	}

	public static void stop(Server svr) {
		if( svr != null ) {
			svr.stop();
			waitFor(() -> !svr.isRunning(), 5000, "server did not stop");
		}
	}

	/**
	 * An ACL built from user file lines, e.g. "alice, pw, Echo|Secret".
	 */
	public static IAccessControlList acl(String... lines) {
		return new FileBasedAcl() {
			{
				for (String line : lines) {
					parseLine(line);
				}
			}
		};
	}

	public static CommandClient connect(Server svr) throws IOException {
		return connect(svr, "localhost");
	}

	public static CommandClient connect(Server svr, String host) throws IOException {
		CommandClient client = newClient(svr, host);
		assertTrue(client.connect(), "connect failed: " + client.getLastConnectError());
		return client;
	}

	public static CommandClient newClient(Server svr, String host) {
		CommandClient client = new CommandClient(host, svr.getLocalPort());
		// Fail fast instead of hanging a test run
		client.setTimeout(5000);
		return client;
	}

	public static void waitFor(BooleanSupplier condition, long timeoutMs, String message) {
		long end = System.currentTimeMillis() + timeoutMs;
		while( !condition.getAsBoolean()) {
			if( System.currentTimeMillis() > end ) {
				throw new AssertionError(message);
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(message, e);
			}
		}
	}

	// ---------- TLS material

	public static KeyStore keyStore() throws Exception {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (InputStream in = ServerTestSupport.class.getResourceAsStream(KEYSTORE)) {
			if( in == null ) {
				throw new IllegalStateException("Missing test resource " + KEYSTORE);
			}
			ks.load(in, PASSWORD);
		}
		return ks;
	}

	public static X509Certificate certificate() throws Exception {
		return (X509Certificate) keyStore().getCertificate(KEY_ALIAS);
	}

	/** Server side TLS context using the test key. */
	public static SSLContext serverContext() throws Exception {
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(keyStore(), PASSWORD);
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), null, null);
		return ctx;
	}

	/** Trust managers that trust the test certificate as if it were CA signed. */
	public static TrustManager[] trustingTestCertificate() throws Exception {
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(keyStore());
		return tmf.getTrustManagers();
	}

	/**
	 * DynamicTrustManager saves approvals in ${user.home}/.bjlTructed. 
	 * Point user.home at target/test-home so tests never touch the real file.
	 * @return the test home directory
	 */
	public static File useTestHome() {
		File home = new File("target", "test-home").getAbsoluteFile();
		home.mkdirs();
		System.setProperty("user.home", home.getPath());
		return home;
	}
}
