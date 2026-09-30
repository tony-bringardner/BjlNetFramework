package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;

/**
 * Connection management: max clients, idle connection cleanup, start / stop, client connect failures.
 */
public class TestServerLimits {

	private static void assertEcho(CommandClient client) throws IOException {
		ICommandResponse resp = client.executeCommand(ServerTestSupport.ECHO, "hi");
		assertTrue(resp.isPositive(), resp.toString());
	}

	/** @return the next line, or null if the connection was closed or reset */
	private static String readOrNull(CommandClient client) {
		try {
			return client.readLine();
		} catch (IOException e) {
			return null;
		}
	}

	@Test
	public void testMaxClientsRejectsWithBusyMessage() throws Exception {
		TestServer svr = ServerTestSupport.create("LimitServer", ServerTestSupport.standardCommands(), null);
		svr.setMaxClients(1);
		svr.setServerBusyMessage("421 too many connections");
		ServerTestSupport.start(svr);
		try {
			CommandClient first = ServerTestSupport.connect(svr);
			assertEcho(first);

			try (CommandClient second = ServerTestSupport.connect(svr)) {
				assertEquals("421 too many connections", second.readLine());
				assertNull(readOrNull(second), "rejected connection should be closed");
			}

			// Once the first client leaves there is room again
			first.close();
			ServerTestSupport.waitFor(() -> svr.getActiveClients().isEmpty(), 5000, "first client not removed");
			try (CommandClient third = ServerTestSupport.connect(svr)) {
				assertEcho(third);
			}
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void testIdleConnectionsClosed() throws Exception {
		TestServer svr = ServerTestSupport.start(ServerTestSupport.create("IdleServer", ServerTestSupport.standardCommands(), null));
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEcho(client);
			svr.setMaxIdleConnection(50);
			Thread.sleep(200);
			svr.runAdmin();
			assertNull(readOrNull(client), "idle connection should have been closed");
			ServerTestSupport.waitFor(() -> svr.getActiveClients().isEmpty(), 5000, "idle client not removed");
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void testActiveConnectionsSurviveAdmin() throws Exception {
		TestServer svr = ServerTestSupport.start(ServerTestSupport.create("ActiveServer", ServerTestSupport.standardCommands(), null));
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEcho(client);
			svr.runAdmin();
			assertEcho(client);
			assertEquals(1, svr.getActiveClients().size());
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void testStartAndStop() {
		TestServer svr = ServerTestSupport.create("StartStopServer", ServerTestSupport.standardCommands(), null);
		assertEquals(-1, svr.getLocalPort());
		ServerTestSupport.start(svr);
		assertTrue(svr.getLocalPort() > 0);
		ServerTestSupport.stop(svr);
		assertFalse(svr.isRunning());
	}

	/**
	 * stop() used to only set a flag, so it waited for accept() to time out
	 * (5 s with the default accept timeout).
	 */
	@Test
	public void testStopIsImmediateWithDefaultAcceptTimeout() {
		TestServer svr = ServerTestSupport.create("QuickStopServer", ServerTestSupport.standardCommands(), null);
		svr.setAcceptTimeout(us.bringardner.net.framework.server.Server.DEFAULT_ACCEPT_TIMEOUT);
		ServerTestSupport.start(svr);
		long start = System.currentTimeMillis();
		svr.stop();
		ServerTestSupport.waitFor(() -> !svr.isRunning(), 10000, "server did not stop");
		long elapsed = System.currentTimeMillis() - start;
		assertTrue(elapsed < 1000, "stop() took " + elapsed + " ms");
	}

	/** A stopped server can be started again (it used to reuse its closed server socket). */
	@Test
	public void testRestartAfterStop() throws Exception {
		TestServer svr = ServerTestSupport.start(ServerTestSupport.create("RestartServer", ServerTestSupport.standardCommands(), null));
		ServerTestSupport.stop(svr);
		ServerTestSupport.start(svr);
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEcho(client);
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void testStopClosesClients() throws Exception {
		TestServer svr = ServerTestSupport.start(ServerTestSupport.create("StopServer", ServerTestSupport.standardCommands(), null));
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEcho(client);
			ServerTestSupport.stop(svr);
			assertNull(readOrNull(client), "stop() should close client connections");
		}
	}

	@Test
	public void testConnectFailureReported() throws Exception {
		int port;
		try (ServerSocket tmp = new ServerSocket(0)) {
			port = tmp.getLocalPort();
		}
		// Nothing is listening on port now
		try (CommandClient client = new CommandClient("localhost", port)) {
			assertFalse(client.connect());
			assertFalse(client.isConnected());
			assertNotNull(client.getLastConnectError());
		}
	}

	@Test
	public void testReconnect() throws Exception {
		TestServer svr = ServerTestSupport.start(ServerTestSupport.create("ReconnectServer", ServerTestSupport.standardCommands(), null));
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEcho(client);
			assertTrue(client.connect());
			assertNull(client.getLastConnectError());
			assertEcho(client);
			// The first connection was closed, not leaked
			ServerTestSupport.waitFor(() -> svr.getActiveClients().size() == 1, 5000, "old connection not closed on reconnect");
		} finally {
			ServerTestSupport.stop(svr);
		}
	}
}
