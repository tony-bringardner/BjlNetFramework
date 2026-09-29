package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.core.SecureBaseObject;
import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.DynamicTrustManager;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator.ManageAs;
import us.bringardner.net.framework.client.ICommandResponse;

/**
 * STARTTLS style upgrade over a real socket, client certificate and host name checks.
 */
public class TestTls {

	private static TestServer svr;

	@BeforeAll
	public static void startServer() throws Exception {
		ServerTestSupport.useTestHome();
		svr = ServerTestSupport.start(ServerTestSupport.create("TlsServer", ServerTestSupport.standardCommands(), ServerTestSupport.serverContext()));
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	@AfterEach
	public void forgetApprovals() throws IOException {
		DynamicTrustManager.removeTrusted("localhost");
		DynamicTrustManager.removeTrusted("127.0.0.1");
	}

	private static void startTls(CommandClient client) throws IOException {
		ICommandResponse resp = client.executeCommand(ServerTestSupport.START_TLS);
		assertEquals(220, resp.getResponseCode(), resp.toString());
		client.negotiateSecureSocket("TLS");
	}

	private static void assertEcho(CommandClient client, String text) throws IOException {
		ICommandResponse resp = client.executeCommand(ServerTestSupport.ECHO, text);
		assertTrue(resp.isPositive(), resp.toString());
		assertEquals(text, resp.getResponseText());
	}

	/** A client context whose DynamicTrustManager trusts the test certificate like a CA signed one. */
	private static SecureBaseObject trustingContext(ManageAs whenUnknown) throws Exception {
		SecureBaseObject ctx = new SecureBaseObject();
		ctx.setTrustManagers(new TrustManager[] {
				new DynamicTrustManager(ServerTestSupport.trustingTestCertificate(), cert -> whenUnknown)
		});
		return ctx;
	}

	@Test
	public void testSelfSignedRejectedByDefault() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			ICommandResponse resp = client.executeCommand(ServerTestSupport.START_TLS);
			assertEquals(220, resp.getResponseCode());
			assertThrows(IOException.class, () -> client.negotiateSecureSocket("TLS"));
			assertFalse(client.isSecure());
		}
	}

	@Test
	public void testTrustAllAcceptsSelfSigned() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			client.setTrustAllCertificates(true);
			startTls(client);
			assertTrue(client.isSecure());
			assertTrue(client.getSocket() instanceof SSLSocket);
			assertEcho(client, "over tls");
		}
	}

	@Test
	public void testTrustedCertificateWithMatchingHost() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr, "localhost")) {
			client.setContext(trustingContext(ManageAs.REJECT));
			startTls(client);
			assertEcho(client, "trusted");
		}
	}

	@Test
	public void testHostNameMismatchRejected() throws Exception {
		// The certificate is only valid for "localhost"
		try (CommandClient client = ServerTestSupport.connect(svr, "127.0.0.1")) {
			client.setContext(trustingContext(ManageAs.REJECT));
			client.executeCommand(ServerTestSupport.START_TLS);
			assertThrows(IOException.class, () -> client.negotiateSecureSocket("TLS"));
		}
	}

	@Test
	public void testHostNameCheckCanBeTurnedOff() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr, "127.0.0.1")) {
			client.setContext(trustingContext(ManageAs.REJECT));
			client.setVerifyHostname(false);
			startTls(client);
			assertEcho(client, "no host check");
		}
	}

	@Test
	public void testApprovalIsTiedToHost() throws Exception {
		AtomicReference<String> askedFor = new AtomicReference<>();
		DynamicTrustManager.CertificateValidator acceptOnce = new DynamicTrustManager.CertificateValidator() {
			@Override
			public ManageAs validate(java.security.cert.X509Certificate cert) {
				return ManageAs.REJECT;
			}

			@Override
			public ManageAs validate(java.security.cert.X509Certificate cert, String host) {
				askedFor.set(host);
				return ManageAs.ACCEPT_ONCE;
			}
		};

		// 1> Approve for localhost
		try (CommandClient client = ServerTestSupport.connect(svr, "localhost")) {
			SecureBaseObject ctx = new SecureBaseObject();
			ctx.setTrustManagers(new TrustManager[] {new DynamicTrustManager(null, acceptOnce)});
			client.setContext(ctx);
			startTls(client);
			assertEquals("localhost", askedFor.get());
			assertEcho(client, "approved");
		}

		// 2> Now trusted for localhost without asking
		askedFor.set(null);
		try (CommandClient client = ServerTestSupport.connect(svr, "localhost")) {
			SecureBaseObject ctx = new SecureBaseObject();
			ctx.setTrustManagers(new TrustManager[] {new DynamicTrustManager(null, cert -> ManageAs.REJECT)});
			client.setContext(ctx);
			startTls(client);
			assertEcho(client, "remembered");
		}
		assertNull(askedFor.get());

		// 3> But not for another host name
		try (CommandClient client = ServerTestSupport.connect(svr, "127.0.0.1")) {
			SecureBaseObject ctx = new SecureBaseObject();
			ctx.setTrustManagers(new TrustManager[] {new DynamicTrustManager(null, cert -> ManageAs.REJECT)});
			client.setContext(ctx);
			client.executeCommand(ServerTestSupport.START_TLS);
			assertThrows(IOException.class, () -> client.negotiateSecureSocket("TLS"));
		}
	}

	@Test
	public void testSecondNegotiationRefused() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			client.setTrustAllCertificates(true);
			startTls(client);
			assertThrows(IllegalStateException.class, () -> client.negotiateSecureSocket("TLS"));
			// the live TLS session is untouched
			assertEcho(client, "still secure");
		}
	}
}
