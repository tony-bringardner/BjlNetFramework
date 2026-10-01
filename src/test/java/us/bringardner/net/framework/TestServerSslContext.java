package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.server.ICommand;

/**
 * Server.getSSLContext(String) (BJL-38): every STARTTLS / AUTH upgrade uses the server's one
 * TLS context, whatever name the client sends, so sessions can be resumed across connections.
 * <p>
 * It used to switch the server's protocol to the requested name and back, building a new
 * context each time: with a configured protocol (TLSv1.3) that differs from the name clients
 * send ("TLS"), no connection could resume another's session, and concurrent callers could get
 * the wrong context.
 */
public class TestServerSslContext {

	private static final String CONFIGURED = "TLSv1.3";
	private static TestServer svr;

	@BeforeAll
	public static void startServer() throws Exception {
		Map<String, ICommand> commands = ServerTestSupport.standardCommands();
		svr = ServerTestSupport.create("SslContextServer", commands, null);
		svr.setKeyStoreType("PKCS12");
		svr.setKeyStoreFileName(ServerTestSupport.KEYSTORE);
		svr.setKeyStorePassword(new String(ServerTestSupport.PASSWORD));
		svr.setProtocol(CONFIGURED);
		// StartTls asks the server for a context by name, like FTP's AUTH TLS and SMTP's STARTTLS
		final TestServer server = svr;
		svr.setConnectionFactory(socket -> new Connection(socket, true) {
			@Override
			public SSLContext getSSLContext(String sslOrTsl) throws IOException {
				return server.getSSLContext(sslOrTsl);
			}
		});
		ServerTestSupport.start(svr);
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	@ParameterizedTest
	@ValueSource(strings = { "TLS", "tls", "SSL", "TLS-C", "TLS-P", "TLSv1.2", "TLSv1.3", " TLS " })
	public void everyMechanismNameGetsTheServersContext(String name) throws Exception {
		SSLContext ctx = svr.getSSLContext();
		assertSame(ctx, svr.getSSLContext(name));
		assertSame(ctx, svr.getSSLContext((String) null));
		assertSame(ctx, svr.getSSLContext(), "the server's own context must not be replaced");
		assertEquals(CONFIGURED, svr.getProtocol(), "the server's protocol must not change");
	}

	@ParameterizedTest
	@ValueSource(strings = { "GSSAPI", "KERBEROS_V4", "", "XTLS", "TL" })
	public void otherMechanismsAreRejected(String name) {
		assertThrows(IOException.class, () -> svr.getSSLContext(name));
	}

	@Test
	public void concurrentUpgradesAlwaysGetTheSameContext() throws Exception {
		SSLContext expected = svr.getSSLContext();
		String[] names = { "TLS", "SSL", "TLSv1.2", null };
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				final int id = t;
				results.add(pool.submit(() -> {
					int wrong = 0;
					for (int i = 0; i < 2000; i++) {
						SSLContext got = (i + id) % 5 == 0 ? svr.getSSLContext() : svr.getSSLContext(names[(i + id) % names.length]);
						if (got != expected) {
							wrong++;
						}
					}
					return wrong;
				}));
			}
			for (Future<Integer> f : results) {
				assertEquals(0, f.get(30, TimeUnit.SECONDS).intValue(), "calls that got another context");
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(CONFIGURED, svr.getProtocol());
	}

	@ParameterizedTest
	@ValueSource(strings = { "TLSv1.3", "TLSv1.2" })
	public void reconnectingClientsResumeTheirSession(String clientProtocol) throws Exception {
		CountingTrust trust = new CountingTrust();
		SSLContext client = SSLContext.getInstance(clientProtocol);
		client.init(null, new TrustManager[] { trust }, null);
		for (int idx = 0; idx < 3; idx++) {
			startTlsAndEcho(client, "hello " + idx);
		}
		assertEquals(1, trust.fullHandshakes.get(), "full handshakes (1 = the later connections resumed)");
	}

	/** Connect, StartTls, one command over TLS (which also delivers TLS 1.3 session tickets), close. */
	private static void startTlsAndEcho(SSLContext client, String text) throws Exception {
		try (Socket plain = new Socket("localhost", svr.getLocalPort())) {
			plain.setSoTimeout(5000);
			OutputStream out = plain.getOutputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(plain.getInputStream(), StandardCharsets.US_ASCII));
			out.write((ServerTestSupport.START_TLS + "\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			String reply = in.readLine();
			assertTrue(reply != null && reply.startsWith("220"), "StartTls reply: " + reply);
			try (SSLSocket tls = (SSLSocket) client.getSocketFactory().createSocket(plain, "localhost", plain.getPort(), true)) {
				tls.startHandshake();
				OutputStream tout = tls.getOutputStream();
				BufferedReader tin = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII));
				tout.write((ServerTestSupport.ECHO + " " + text + "\r\n").getBytes(StandardCharsets.US_ASCII));
				tout.flush();
				String echo = tin.readLine();
				assertTrue(echo != null && echo.endsWith(text), "Echo reply: " + echo);
			}
		}
	}

	/** Trusts the test certificate; the server's certificate is only checked on full handshakes. */
	private static final class CountingTrust extends X509ExtendedTrustManager {
		final AtomicInteger fullHandshakes = new AtomicInteger();
		private final X509ExtendedTrustManager delegate;

		CountingTrust() throws Exception {
			X509ExtendedTrustManager found = null;
			for (TrustManager tm : ServerTestSupport.trustingTestCertificate()) {
				if (tm instanceof X509ExtendedTrustManager) {
					found = (X509ExtendedTrustManager) tm;
				}
			}
			delegate = found;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			fullHandshakes.incrementAndGet();
			delegate.checkServerTrusted(chain, authType, socket);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			fullHandshakes.incrementAndGet();
			delegate.checkServerTrusted(chain, authType, engine);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			fullHandshakes.incrementAndGet();
			delegate.checkServerTrusted(chain, authType);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			throw new CertificateException("client side only");
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			throw new CertificateException("client side only");
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			throw new CertificateException("client side only");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return delegate.getAcceptedIssuers();
		}
	}
}
