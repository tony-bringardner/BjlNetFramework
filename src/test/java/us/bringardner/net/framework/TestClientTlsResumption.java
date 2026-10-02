package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * The server's key is only used on a full handshake, so counting key lookups counts full
 * handshakes. Every Client used to have its own TLS context and rebuilt it on each
 * negotiation, so every connection was a full handshake.
 */
public class TestClientTlsResumption {

	private static final AtomicInteger fullHandshakes = new AtomicInteger();
	private static TestServer svr;

	@BeforeAll
	public static void startServer() throws Exception {
		ServerTestSupport.useTestHome();
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ServerTestSupport.keyStore(), ServerTestSupport.PASSWORD);
		X509ExtendedKeyManager real = (X509ExtendedKeyManager) kmf.getKeyManagers()[0];
		SSLContext tls = SSLContext.getInstance("TLS");
		tls.init(new KeyManager[] { new CountingKeyManager(real) }, null, null);
		svr = ServerTestSupport.start(ServerTestSupport.create("TlsResumeServer", ServerTestSupport.standardCommands(), tls));
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	@BeforeEach
	public void reset() {
		fullHandshakes.set(0);
	}

	/** Connect, STARTTLS, one command (which also delivers TLS 1.3 session tickets), close. */
	private static String session(CommandClient client) throws IOException {
		ICommandResponse resp = client.executeCommand(ServerTestSupport.START_TLS);
		assertEquals(220, resp.getResponseCode(), resp.toString());
		client.negotiateSecureSocket("TLS");
		resp = client.executeCommand(ServerTestSupport.ECHO, "hello");
		assertTrue(resp.isPositive(), resp.toString());
		return ((SSLSocket) client.getSocket()).getSession().getProtocol();
	}

	@Test
	public void defaultContextIsSharedAndResumes() throws Exception {
		for (int i = 0; i < 3; i++) {
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				client.setTrustAllCertificates(true);
				session(client);
				assertNull(client.getContext(), "the shared default is not exposed for changes");
			}
		}
		// The shared context outlives the test, so another test may already have a session
		// for this server: at most the first connection does a full handshake.
		assertTrue(fullHandshakes.get() <= 1, "full handshakes (at most 1 = the later connections resumed): " + fullHandshakes.get());
	}

	@Test
	public void ownContextIsReusedAcrossReconnects() throws Exception {
		SecureBaseObject ctx = new SecureBaseObject();
		ctx.setTrustManagers(new TrustManager[] {
				new DynamicTrustManager(ServerTestSupport.trustingTestCertificate(), cert -> ManageAs.REJECT) });
		SSLContext first = null;
		for (int i = 0; i < 3; i++) {
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				client.setContext(ctx);
				session(client);
				if (first == null) {
					first = ctx.getSSLContext();
				}
				assertTrue(first == ctx.getSSLContext(), "the context must not be rebuilt");
			}
		}
		assertEquals(1, fullHandshakes.get(), "full handshakes (1 = the later connections resumed)");
	}

	@Test
	public void trustModesDontShareSessions() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			client.setTrustAllCertificates(true);
			session(client);
		}
		int afterTrustAll = fullHandshakes.get();
		// A validating client must not resume a session a trust-all client accepted
		SecureBaseObject ctx = new SecureBaseObject();
		ctx.setTrustManagers(new TrustManager[] {
				new DynamicTrustManager(ServerTestSupport.trustingTestCertificate(), cert -> ManageAs.REJECT) });
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			client.setContext(ctx);
			session(client);
		}
		assertEquals(afterTrustAll + 1, fullHandshakes.get(), "a different trust configuration means a full handshake");
	}

	private static final class CountingKeyManager extends X509ExtendedKeyManager {
		private final X509ExtendedKeyManager real;

		CountingKeyManager(X509ExtendedKeyManager real) {
			this.real = real;
		}

		@Override
		public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
			String alias = real.chooseEngineServerAlias(keyType, issuers, engine);
			if (alias != null) {
				fullHandshakes.incrementAndGet();
			}
			return alias;
		}

		@Override
		public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
			String alias = real.chooseServerAlias(keyType, issuers, socket);
			if (alias != null) {
				fullHandshakes.incrementAndGet();
			}
			return alias;
		}

		@Override
		public String[] getClientAliases(String keyType, Principal[] issuers) {
			return real.getClientAliases(keyType, issuers);
		}

		@Override
		public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
			return real.chooseClientAlias(keyType, issuers, socket);
		}

		@Override
		public String[] getServerAliases(String keyType, Principal[] issuers) {
			return real.getServerAliases(keyType, issuers);
		}

		@Override
		public X509Certificate[] getCertificateChain(String alias) {
			return real.getCertificateChain(alias);
		}

		@Override
		public PrivateKey getPrivateKey(String alias) {
			return real.getPrivateKey(alias);
		}
	}
}
