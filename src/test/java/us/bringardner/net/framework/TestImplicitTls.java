package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;
import us.bringardner.net.framework.server.ICommand;
import us.bringardner.net.framework.server.Server;

/**
 * A server that is TLS from the first byte (setSecure(true)), configured from a key store,
 * plus startup error reporting and the Server SSL helper methods.
 */
public class TestImplicitTls {

	private static final String IS_SECURE = "IsSecure";
	private static TestServer svr;

	/** A secure server using the test key store, not started. */
	private static TestServer secureServer(String name, String keyStore, String password) {
		Map<String, ICommand> commands = ServerTestSupport.standardCommands();
		commands.put(IS_SECURE, ServerTestSupport.command(IS_SECURE, false,
				(p, c) -> p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK, "" + p.getConnection().isSecure())));
		TestServer ret = ServerTestSupport.create(name, commands, null);
		ret.setSecure(true);
		ret.setKeyStoreType("PKCS12");
		ret.setKeyStoreFileName(keyStore);
		ret.setKeyStorePassword(password);
		return ret;
	}

	@BeforeAll
	public static void startServer() throws Exception {
		svr = secureServer("ImplicitTlsServer", ServerTestSupport.KEYSTORE, new String(ServerTestSupport.PASSWORD));
		svr.startAndWait(5000);
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	/** A client that speaks TLS from the first byte and trusts the test certificate. */
	private static CommandClient tlsClient(String host) throws Exception {
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, ServerTestSupport.trustingTestCertificate(), null);
		CommandClient client = ServerTestSupport.newClient(svr, host);
		client.setSocketFactory(ctx.getSocketFactory());
		return client;
	}

	@Test
	public void testTlsFromFirstByte() throws Exception {
		try (CommandClient client = tlsClient("localhost")) {
			assertTrue(client.connect(), "connect failed: " + client.getLastConnectError());
			assertTrue(client.isSecure());
			assertTrue(client.getSocket() instanceof SSLSocket);

			ICommandResponse resp = client.executeCommand(ServerTestSupport.ECHO, "secure", "echo");
			assertTrue(resp.isPositive(), resp.toString());
			assertEquals("secure echo", resp.getResponseText());

			// The server side knows the connection is secure
			assertEquals("true", client.executeCommand(IS_SECURE).getResponseText());
		}
	}

	@Test
	public void testStartTlsRefusedOnSecureConnection() throws Exception {
		try (CommandClient client = tlsClient("localhost")) {
			assertTrue(client.connect());
			// The command replies 220, then the server refuses to nest TLS and reports an error
			assertEquals(220, client.executeCommand(ServerTestSupport.START_TLS).getResponseCode());
			String next = client.readLine();
			assertTrue(next != null && next.startsWith("500"), "expected a 500 reply, got " + next);
			// and the TLS session is still usable
			assertTrue(client.executeCommand(ServerTestSupport.ECHO, "ok").isPositive());
		}
	}

	@Test
	public void testHostNameChecked() throws Exception {
		// The certificate is only valid for localhost. The handshake happens on first use.
		try (CommandClient client = tlsClient("127.0.0.1")) {
			assertTrue(client.connect());
			assertThrows(IOException.class, () -> client.executeCommand(ServerTestSupport.ECHO, "x"));
		}
	}

	@Test
	public void testPlainClientGetsNoReply() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			try {
				ICommandResponse resp = client.executeCommand(ServerTestSupport.ECHO, "plain");
				assertFalse(resp.isPositive(), resp.toString());
			} catch (IOException expected) {
				// closed or reset by the server's failed handshake
			}
		}
	}

	@Test
	public void testWrongPasswordFailsStart() {
		TestServer bad = secureServer("BadPasswordServer", ServerTestSupport.KEYSTORE, "wrong password");
		try {
			IOException e = assertThrows(IOException.class, () -> bad.startAndWait(5000));
			assertNotNull(bad.getStartupError());
			assertFalse(bad.isRunning());
			assertNotNull(e.getMessage());
		} finally {
			ServerTestSupport.stop(bad);
		}
	}

	@Test
	public void testMissingKeyStoreFailsStart() {
		TestServer bad = secureServer("MissingKeyStoreServer", "/no-such-keystore.p12", "changeit");
		try {
			assertThrows(IOException.class, () -> bad.startAndWait(5000));
			assertNotNull(bad.getStartupError());
		} finally {
			ServerTestSupport.stop(bad);
		}
	}

	@Test
	public void testMissingFactoriesFailStart() {
		Server bad = new Server(0, "NoFactoriesServer");
		IOException e = assertThrows(IOException.class, () -> bad.startAndWait(5000));
		assertTrue(e.getMessage().contains("factory"), e.getMessage());
	}

	@Test
	public void testFactories() {
		assertTrue(svr.getServerSocketFactory(true) instanceof SSLServerSocketFactory);
		assertSame(ServerSocketFactory.getDefault(), svr.getServerSocketFactory(false));
		assertTrue(svr.getSocketFactory(true) instanceof SSLSocketFactory);
		assertSame(SocketFactory.getDefault(), svr.getSocketFactory(false));

		// A misconfigured server fails loudly instead of returning null
		TestServer bad = secureServer("BadFactoryServer", "/no-such-keystore.p12", "changeit");
		assertThrows(IllegalStateException.class, () -> bad.getServerSocketFactory(true));
		assertThrows(IllegalStateException.class, () -> bad.getSocketFactory(true));
	}

	@Test
	public void testSslContextForProtocol() throws Exception {
		// One context for every mechanism name (BJL-38, see TestServerSslContext)
		String before = svr.getProtocol();
		SSLContext ctx = svr.getSSLContext("TLSv1.2");
		assertSame(svr.getSSLContext(), ctx);
		assertEquals(before, svr.getProtocol(), "the server's own protocol must not change");
		assertNotNull(svr.getSSLContext((String) null));
	}

	@Test
	public void testSslContextFromKeyFile() throws Exception {
		String path = new File(ServerTestSupport.class.getResource(ServerTestSupport.KEYSTORE).toURI()).getPath();
		String alg = KeyManagerFactory.getDefaultAlgorithm();
		SSLContext ctx = svr.getSSLContext("TLS", alg, "PKCS12", "changeit", path);
		assertNotNull(ctx.getServerSocketFactory());

		assertThrows(IOException.class, () -> svr.getSSLContext("TLS", alg, "PKCS12", "changeit", path + ".missing"));
		assertThrows(IOException.class, () -> svr.getSSLContext("TLS", alg, "PKCS12", "wrong", path));
	}
}
