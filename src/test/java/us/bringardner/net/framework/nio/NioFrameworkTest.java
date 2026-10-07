package us.bringardner.net.framework.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.server.IPrincipal;
import us.bringardner.net.framework.server.Permission;
import us.bringardner.net.framework.server.PropertyAuthenticator;

public class NioFrameworkTest {

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

	/** Collects what a client connection receives */
	private static class Collector implements INioHandler {
		final BlockingQueue<ByteBuffer> frames = new LinkedBlockingQueue<ByteBuffer>();
		final CountDownLatch closed = new CountDownLatch(1);
		final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

		@Override
		public void onMessage(INioConnection connection, ByteBuffer frame) {
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

	/** Echo lines with a greeting, "quit" closes after the reply */
	private static class LineEcho implements INioHandler {
		@Override
		public void onConnect(INioConnection connection) throws Exception {
			connection.writeLine("220 ready");
		}

		@Override
		public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
			String line = LineFrameDecoder.toString(frame);
			connection.writeLine("echo "+line);
			if( line.equals("quit") ) {
				connection.closeAfterFlush();
			}
		}
	}

	private void startServer(INioHandlerFactory factory, java.util.function.Supplier<IFrameDecoder> decoder) throws IOException {
		server = new NioServer(0, "test");
		server.setHandlerFactory(factory);
		server.setDecoderFactory(decoder);
		server.setReactorCount(2);
		server.startAndWait(5000);
		client = new NioClient("test-client");
	}

	@Test
	public void lineEcho() throws Exception {
		startServer(LineEcho::new, LineFrameDecoder::new);
		Collector c = new Collector();
		INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());
		assertEquals("220 ready", c.nextLine());

		// Several lines in one write, and a line split over two writes
		con.write("one\r\ntwo\nthr".getBytes(StandardCharsets.UTF_8));
		con.write("ee\r\n".getBytes(StandardCharsets.UTF_8));
		assertEquals("echo one", c.nextLine());
		assertEquals("echo two", c.nextLine());
		assertEquals("echo three", c.nextLine());

		con.writeLine("quit");
		assertEquals("echo quit", c.nextLine());
		assertTrue(c.closed.await(5, TimeUnit.SECONDS), "Server should close after quit");
		assertFalse(con.isOpen());
		assertThrows(IOException.class, () -> con.writeLine("late"));
		waitFor(() -> server.getConnectionCount() == 0);
	}

	/**
	 * Like SSH: an identification line, then length prefixed packets. The client sends both
	 * in one write, so the bytes after the line must reach the new decoder.
	 */
	@Test
	public void switchDecoder() throws Exception {
		startServer(() -> new INioHandler() {
			boolean versionSeen;
			@Override
			public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
				if( !versionSeen ) {
					versionSeen = true;
					assertEquals("SSH-2.0-Test", LineFrameDecoder.toString(frame));
					connection.setDecoder(new LengthFieldFrameDecoder(4, 1024*1024, true));
					connection.writeLine("SSH-2.0-BjlSsh");
				} else {
					byte[] payload = new byte[frame.remaining()];
					frame.get(payload);
					connection.write(LengthFieldFrameDecoder.frame(payload));
				}
			}
		}, LineFrameDecoder::new);

		Collector c = new Collector();
		INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());
		byte[] small = "kexinit".getBytes(StandardCharsets.UTF_8);
		ByteBuffer packet = LengthFieldFrameDecoder.frame(small);
		byte[] line = "SSH-2.0-Test\r\n".getBytes(StandardCharsets.UTF_8);
		ByteBuffer both = ByteBuffer.allocate(line.length+packet.remaining());
		both.put(line).put(packet).flip();
		con.write(both);

		assertEquals("SSH-2.0-BjlSsh", c.nextLine());
		con.setDecoder(new LengthFieldFrameDecoder(4, 1024*1024, true));
		assertArrayEquals(small, bytes(c.frames.poll(5, TimeUnit.SECONDS)));

		// A packet larger than the initial input buffer and the socket buffers
		byte[] big = new byte[600*1024];
		new Random(1).nextBytes(big);
		con.write(LengthFieldFrameDecoder.frame(big));
		assertArrayEquals(big, bytes(c.frames.poll(10, TimeUnit.SECONDS)));
		assertTrue(c.errors.isEmpty(), c.errors.toString());
	}

	/** Handlers on an executor: still one at a time and in order for each connection */
	@Test
	public void handlerExecutorKeepsOrder() throws Exception {
		executor = Executors.newFixedThreadPool(8);
		server = new NioServer(0, "test-exec");
		server.setHandlerFactory(() -> (connection, frame) -> {
			Thread.sleep(0, 1000);
			connection.write(LengthFieldFrameDecoder.frame(bytes(frame)));
		});
		server.setDecoderFactory(() -> new LengthFieldFrameDecoder(4, 1024, true));
		server.setHandlerExecutor(executor);
		server.startAndWait(5000);
		client = new NioClient();
		client.setHandlerExecutor(executor);

		int clients = 5;
		int count = 500;
		List<Collector> collectors = new ArrayList<Collector>();
		for (int i = 0; i < clients; i++) {
			Collector c = new Collector();
			collectors.add(c);
			INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LengthFieldFrameDecoder(4, 1024, true));
			for (int j = 0; j < count; j++) {
				con.write(LengthFieldFrameDecoder.frame(ByteBuffer.allocate(4).putInt(j).array()));
			}
		}
		for (Collector c : collectors) {
			for (int j = 0; j < count; j++) {
				ByteBuffer b = c.frames.poll(5, TimeUnit.SECONDS);
				assertNotNull(b, "Frame "+j+" missing");
				assertEquals(j, b.getInt());
			}
		}
		assertEquals(clients, server.getConnectionCount());
	}

	@Test
	public void maxClients() throws Exception {
		server = new NioServer(0, "test-max");
		server.setHandlerFactory(LineEcho::new);
		server.setDecoderFactory(LineFrameDecoder::new);
		server.setMaxConnections(1);
		server.setServerBusyMessage("421 busy");
		server.startAndWait(5000);
		client = new NioClient();
		InetSocketAddress addr = new InetSocketAddress("localhost", server.getLocalPort());

		Collector first = new Collector();
		client.connectAndWait(addr, first, new LineFrameDecoder());
		assertEquals("220 ready", first.nextLine());

		Collector second = new Collector();
		client.connectAndWait(addr, second, new LineFrameDecoder());
		assertEquals("421 busy", second.nextLine());
		assertTrue(second.closed.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void idleConnectionClosed() throws Exception {
		server = new NioServer(0, "test-idle");
		server.setHandlerFactory(LineEcho::new);
		server.setDecoderFactory(LineFrameDecoder::new);
		server.setMaxIdleTime(300);
		server.startAndWait(5000);
		client = new NioClient();
		Collector c = new Collector();
		client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());
		assertEquals("220 ready", c.nextLine());
		assertTrue(c.closed.await(5, TimeUnit.SECONDS), "Idle connection should be closed");
	}

	@Test
	public void lineTooLong() throws Exception {
		List<Throwable> serverErrors = Collections.synchronizedList(new ArrayList<Throwable>());
		startServer(() -> new LineEcho() {
			@Override
			public void onError(INioConnection connection, Throwable error) {
				serverErrors.add(error);
			}
		}, () -> new LineFrameDecoder(100));
		Collector c = new Collector();
		INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());
		assertEquals("220 ready", c.nextLine());
		con.write(new byte[1000]);
		assertTrue(c.closed.await(5, TimeUnit.SECONDS), "Server should close on a line that's too long");
		waitFor(() -> !serverErrors.isEmpty());
	}

	@Test
	public void connectRefused() throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		client = new NioClient();
		assertThrows(IOException.class, () -> client.connectAndWait("localhost", port, new Collector()));
	}

	@Test
	public void startFailsOnPortInUse() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			server = new NioServer(s.getLocalPort(), LineEcho::new);
			server.setReuseAddress(false);
			assertThrows(IOException.class, () -> server.startAndWait(5000));
		}
	}

	/** Login with the framework's PropertyAuthenticator, then check permissions per connection */
	@Test
	public void accessControl() throws Exception {
		String name = "nio-auth-test";
		System.setProperty(NioServer.AUTHENTICATION_PROVIDER_PROPERTY, PropertyAuthenticator.class.getName());
		System.setProperty(name+".user0", "alice, secret, shell|sftp");
		try {
			server = new NioServer(0, name);
			server.setDecoderFactory(LineFrameDecoder::new);
			// LOGIN user password, CAN permission
			server.setHandlerFactory(() -> (connection, frame) -> {
				String[] parts = LineFrameDecoder.toString(frame).split(" ");
				if( parts[0].equals("LOGIN") ) {
					IPrincipal user = connection.getServer().authenticate(parts[1], parts[2].getBytes(StandardCharsets.UTF_8));
					connection.setPrincipal(user);
					connection.writeLine(user == null ? "denied" : "welcome "+user.getName());
				} else {
					connection.writeLine(connection.isAuthorized(new Permission(parts[1])) ? "yes" : "no");
				}
			});
			server.startAndWait(5000);
			client = new NioClient();
			Collector c = new Collector();
			INioConnection con = client.connectAndWait(new InetSocketAddress("localhost", server.getLocalPort()), c, new LineFrameDecoder());

			con.writeLine("CAN shell");
			assertEquals("no", c.nextLine(), "Not logged in yet");
			con.writeLine("LOGIN alice wrong");
			assertEquals("denied", c.nextLine());
			con.writeLine("LOGIN bob secret");
			assertEquals("denied", c.nextLine());
			con.writeLine("LOGIN alice secret");
			assertEquals("welcome alice", c.nextLine());
			con.writeLine("CAN shell");
			assertEquals("yes", c.nextLine());
			con.writeLine("CAN admin");
			assertEquals("no", c.nextLine());
		} finally {
			System.clearProperty(NioServer.AUTHENTICATION_PROVIDER_PROPERTY);
			System.clearProperty(name+".user0");
		}
	}

	private static byte[] bytes(ByteBuffer b) {
		assertNotNull(b, "No frame received");
		byte[] ret = new byte[b.remaining()];
		b.duplicate().get(ret);
		return ret;
	}

	private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis()+5000;
		while( !condition.getAsBoolean() ) {
			assertTrue(System.currentTimeMillis() < end, "Timed out waiting");
			Thread.sleep(10);
		}
	}
}
