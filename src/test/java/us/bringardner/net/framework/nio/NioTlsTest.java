package us.bringardner.net.framework.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TLS from the first byte and STARTTLS, with the framework's test key store
 * (a self-signed certificate for CN=localhost, SAN dns:localhost).
 */
public class NioTlsTest {

	private static final String KEYSTORE = "/test-keystore.p12";
	private static final String PASSWORD = "changeit";

	private NioServer server;
	private NioClient client;
	private ExecutorService executor;

	@AfterEach
	public void tearDown() throws InterruptedException {
		if( client != null ) {
			client.close();
		}
		if( server != null ) {
			server.stop(5000, false);
		}
		if( executor != null ) {
			executor.shutdownNow();
		}
	}

	private static class Collector implements INioHandler {
		final BlockingQueue<ByteBuffer> frames = new LinkedBlockingQueue<ByteBuffer>();
		final CountDownLatch closed = new CountDownLatch(1);
		final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

		@Override
		public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
			frames.add(frame);
		}

		@Override
		public void onError(INioConnection connection, Throwable error) {
			errors.add(error);
		}

		@Override
		public void onClose(INioConnection connection) {
			closed.countDown();
		}

		String nextLine() throws InterruptedException {
			ByteBuffer b = frames.poll(5, TimeUnit.SECONDS);
			assertNotNull(b, "No line received");
			return LineFrameDecoder.toString(b);
		}
	}

	/**
	 * Greets, echoes lines with the connection's TLS state, "STARTTLS" switches to TLS,
	 * "quit" closes after the reply.
	 */
	private static class Echo implements INioHandler {
		final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

		@Override
		public void onConnect(INioConnection connection) throws Exception {
			connection.writeLine("220 ready secure="+connection.isSecure());
		}

		@Override
		public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
			String line = LineFrameDecoder.toString(frame);
			if( line.equals("STARTTLS") ) {
				connection.writeLine("220 Ready to start TLS");
				connection.startTls();
				return;
			}
			connection.writeLine("echo "+line+" secure="+connection.isSecure());
			if( line.equals("quit") ) {
				connection.closeAfterFlush();
			}
		}

		@Override
		public void onError(INioConnection connection, Throwable error) {
			errors.add(error);
		}
	}

	private static NioServer secureServer(String name, boolean implicit, INioHandlerFactory factory) {
		NioServer ret = new NioServer(0, name);
		ret.setSecure(implicit);
		ret.setKeyStoreType("PKCS12");
		ret.setKeyStoreFileName(KEYSTORE);
		ret.setKeyStorePassword(PASSWORD);
		ret.setHandlerFactory(factory);
		ret.setDecoderFactory(LineFrameDecoder::new);
		return ret;
	}

	/** Trusts the test certificate (and nothing else) */
	private static TrustManager[] trustTestCertificate() throws Exception {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (InputStream in = NioTlsTest.class.getResourceAsStream(KEYSTORE)) {
			ks.load(in, PASSWORD.toCharArray());
		}
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ks);
		return tmf.getTrustManagers();
	}

	private NioClient trustingClient(boolean implicit) throws Exception {
		NioClient ret = new NioClient("tls-client");
		ret.setSecure(implicit);
		ret.setTrustManagers(trustTestCertificate());
		return ret;
	}

	@Test
	public void implicitTls() throws Exception {
		Echo echo = new Echo();
		server = secureServer("tls-implicit", true, () -> echo);
		server.startAndWait(5000);
		client = trustingClient(true);

		Collector c = new Collector();
		// The host name is checked against the certificate (localhost)
		INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());
		assertTrue(con.isSecure(), "The future completes after the handshake");
		assertNotNull(con.getSslSession());
		assertEquals("220 ready secure=true", c.nextLine(), "onConnect runs after the handshake");

		con.writeLine("hello");
		assertEquals("echo hello secure=true", c.nextLine());
		con.writeLine("quit");
		assertEquals("echo quit secure=true", c.nextLine());
		assertTrue(c.closed.await(5, TimeUnit.SECONDS), "The server closes (close_notify) after quit");
		assertTrue(c.errors.isEmpty(), c.errors.toString());
		assertTrue(echo.errors.isEmpty(), echo.errors.toString());
	}

	@Test
	public void startTls() throws Exception {
		Echo echo = new Echo();
		server = secureServer("tls-start", false, () -> echo);
		server.startAndWait(5000);
		client = trustingClient(false);

		Collector c = new Collector() {
			@Override
			public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
				// Switch before the test thread sees the reply, so what it writes next is encrypted
				if( LineFrameDecoder.toString(frame).equals("220 Ready to start TLS") ) {
					connection.startTls();
				}
				super.onMessage(connection, frame);
			}
		};
		INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());
		assertEquals("220 ready secure=false", c.nextLine());
		con.writeLine("before");
		assertEquals("echo before secure=false", c.nextLine());
		assertNull(con.getSslSession());

		con.writeLine("STARTTLS");
		assertEquals("220 Ready to start TLS", c.nextLine());
		// Sent before the handshake is done: queued, then encrypted
		con.writeLine("after");
		assertEquals("echo after secure=true", c.nextLine());
		assertTrue(con.isSecure());
		assertThrows(IllegalStateException.class, con::startTls, "TLS inside TLS");

		con.writeLine("quit");
		assertEquals("echo quit secure=true", c.nextLine());
		assertTrue(c.closed.await(5, TimeUnit.SECONDS));
		assertTrue(c.errors.isEmpty(), c.errors.toString());
		assertTrue(echo.errors.isEmpty(), echo.errors.toString());
	}

	/** Frames much larger than a TLS record, with handlers on an executor */
	@Test
	public void largeFrames() throws Exception {
		executor = Executors.newFixedThreadPool(4);
		server = secureServer("tls-large", true, () -> (connection, frame) -> {
			byte[] payload = new byte[frame.remaining()];
			frame.get(payload);
			connection.write(LengthFieldFrameDecoder.frame(payload));
		});
		server.setDecoderFactory(() -> new LengthFieldFrameDecoder(4, 2*1024*1024, true));
		server.setMaxInputBuffer(4*1024*1024);
		server.setHandlerExecutor(executor);
		server.startAndWait(5000);
		client = trustingClient(true);
		client.setHandlerExecutor(executor);
		client.setMaxInputBuffer(4*1024*1024);

		Collector c = new Collector();
		INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LengthFieldFrameDecoder(4, 2*1024*1024, true));
		Random random = new Random(7);
		List<byte[]> sent = new ArrayList<byte[]>();
		for (int size : new int[] {1, 100, 16*1024, 70*1024, 1500*1024}) {
			byte[] data = new byte[size];
			random.nextBytes(data);
			sent.add(data);
			con.write(LengthFieldFrameDecoder.frame(data));
		}
		for (byte[] data : sent) {
			ByteBuffer b = c.frames.poll(10, TimeUnit.SECONDS);
			assertNotNull(b, "Frame of "+data.length+" bytes missing");
			byte[] got = new byte[b.remaining()];
			b.get(got);
			assertArrayEquals(data, got);
		}
		assertTrue(c.errors.isEmpty(), c.errors.toString());
	}

	@Test
	public void untrustedCertificateFails() throws Exception {
		server = secureServer("tls-untrusted", true, Echo::new);
		server.startAndWait(5000);
		// The JVM's default trust store doesn't know the self-signed test certificate
		client = new NioClient("tls-untrusted-client");
		client.setSecure(true);
		client.setTrustManagers(null);
		Collector c = new Collector();
		IOException e = assertThrows(IOException.class,
				() -> client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder()));
		assertTrue(e instanceof SSLException, "Expected an SSLException, got "+e);
	}

	@Test
	public void hostNameMismatchFails() throws Exception {
		server = secureServer("tls-host", true, Echo::new);
		server.startAndWait(5000);
		client = trustingClient(true);
		Collector c = new Collector();
		// The certificate is for localhost only
		assertThrows(SSLException.class,
				() -> client.connectAndWait(new InetSocketAddress("127.0.0.1", server.getLocalPort()), c, new LineFrameDecoder()));

		// Without the host name check the same certificate is accepted
		client.setVerifyHostname(false);
		INioConnection con = client.connectAndWait(new InetSocketAddress("127.0.0.1", server.getLocalPort()), c, new LineFrameDecoder());
		assertTrue(con.isSecure());
	}

	@Test
	public void badKeyStoreIsAStartupError() {
		server = secureServer("tls-bad", true, Echo::new);
		server.setKeyStorePassword("wrong");
		assertThrows(IOException.class, () -> server.startAndWait(5000));
		assertFalse(server.isRunning());
	}
}
