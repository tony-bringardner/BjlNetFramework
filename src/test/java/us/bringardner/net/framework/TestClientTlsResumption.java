package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.core.SecureBaseObject;
import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.DynamicTrustManager;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator.ManageAs;
import us.bringardner.net.framework.client.ICommandResponse;

/**
 * Clients that reconnect resume their TLS session (BJL-39).
 * <p>
 * Every Client used to have its own TLS context and rebuilt it on each negotiation, so every
 * connection was a full handshake.
 * <p>
 * A resumed session keeps the creation time of the session it resumes (TLS 1.2 and 1.3), so
 * sessions with the same creation time came from one full handshake. This used to count the
 * server's key lookups instead, but JDK 25 looks the key up on resumed handshakes too, so it
 * reported 3 full handshakes where there was 1 (BJL-54; javax.net.debug shows one server
 * Certificate message on both JDK 21 and 25).
 */
public class TestClientTlsResumption {

	private static TestServer svr;

	@BeforeAll
	public static void startServer() throws Exception {
		ServerTestSupport.useTestHome();
		svr = ServerTestSupport.start(ServerTestSupport.create("TlsResumeServer", ServerTestSupport.standardCommands(),
				ServerTestSupport.serverContext()));
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	/**
	 * STARTTLS and one command (which also delivers TLS 1.3 session tickets).
	 * @return the TLS session's creation time: the same as an earlier session's when it was resumed
	 */
	private static long session(CommandClient client) throws IOException {
		ICommandResponse resp = client.executeCommand(ServerTestSupport.START_TLS);
		assertEquals(220, resp.getResponseCode(), resp.toString());
		client.negotiateSecureSocket("TLS");
		resp = client.executeCommand(ServerTestSupport.ECHO, "hello");
		assertTrue(resp.isPositive(), resp.toString());
		long created = ((SSLSocket) client.getSocket()).getSession().getCreationTime();
		try {
			// Full handshakes in the same millisecond would look like one
			Thread.sleep(5);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return created;
	}

	@Test
	public void defaultContextIsSharedAndResumes() throws Exception {
		Set<Long> fullHandshakes = new HashSet<>();
		for (int i = 0; i < 3; i++) {
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				client.setTrustAllCertificates(true);
				fullHandshakes.add(session(client));
				assertNull(client.getContext(), "the shared default is not exposed for changes");
			}
		}
		// The shared context outlives the test, so the first connection may resume a session
		// from another test; either way all three come from one full handshake.
		assertEquals(1, fullHandshakes.size(), "full handshakes (1 = the later connections resumed)");
	}

	@Test
	public void ownContextIsReusedAcrossReconnects() throws Exception {
		SecureBaseObject ctx = new SecureBaseObject();
		ctx.setTrustManagers(new TrustManager[] {
				new DynamicTrustManager(ServerTestSupport.trustingTestCertificate(), cert -> ManageAs.REJECT) });
		SSLContext first = null;
		Set<Long> fullHandshakes = new HashSet<>();
		for (int i = 0; i < 3; i++) {
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				client.setContext(ctx);
				fullHandshakes.add(session(client));
				if (first == null) {
					first = ctx.getSSLContext();
				}
				assertTrue(first == ctx.getSSLContext(), "the context must not be rebuilt");
			}
		}
		assertEquals(1, fullHandshakes.size(), "full handshakes (1 = the later connections resumed)");
	}

	@Test
	public void trustModesDontShareSessions() throws Exception {
		long trustAll;
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			client.setTrustAllCertificates(true);
			trustAll = session(client);
		}
		// A validating client must not resume a session a trust-all client accepted
		SecureBaseObject ctx = new SecureBaseObject();
		ctx.setTrustManagers(new TrustManager[] {
				new DynamicTrustManager(ServerTestSupport.trustingTestCertificate(), cert -> ManageAs.REJECT) });
		long validating;
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			client.setContext(ctx);
			validating = session(client);
		}
		assertNotEquals(trustAll, validating, "a different trust configuration means a full handshake");
	}
}
