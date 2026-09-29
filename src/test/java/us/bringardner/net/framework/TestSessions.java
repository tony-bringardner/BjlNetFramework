package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;

/**
 * Server session behavior: error handling, authorization, greeting, disconnects and line length.
 */
public class TestSessions {

	private static TestServer svr;

	@BeforeAll
	public static void startServer() {
		svr = ServerTestSupport.create("SessionServer", ServerTestSupport.standardCommands(), null);
		svr.setAccessControl(ServerTestSupport.acl(
				"alice, alicepw, Echo|Secret",
				"bob, bobpw, Echo"));
		ServerTestSupport.start(svr);
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	private static void assertEcho(CommandClient client) throws IOException {
		ICommandResponse resp = client.executeCommand(ServerTestSupport.ECHO, "still", "here");
		assertTrue(resp.isPositive(), resp.toString());
		assertEquals("still here", resp.getResponseText());
	}

	@Test
	public void testUnknownCommandKeepsSession() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			ICommandResponse resp = client.executeCommand("NoSuchCommand");
			assertEquals(500, resp.getResponseCode());
			assertTrue(resp.getResponseText().contains("Not a valid command"), resp.toString());
			assertEcho(client);
		}
	}

	@Test
	public void testFailingCommandKeepsSessionAndHidesDetails() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			ICommandResponse resp = client.executeCommand(ServerTestSupport.FAIL);
			assertEquals(500, resp.getResponseCode());
			assertFalse(resp.getResponseText().contains(ServerTestSupport.FAIL_DETAIL), "exception text leaked: " + resp);
			assertFalse(resp.getResponseText().contains("IllegalStateException"), "exception type leaked: " + resp);
			assertEcho(client);
		}
	}

	@Test
	public void testRefusedWhenNotLoggedIn() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			ICommandResponse resp = client.executeCommand(ServerTestSupport.SECRET);
			assertEquals(500, resp.getResponseCode());
			assertTrue(resp.getResponseText().contains("not authorized"), resp.toString());
			assertEcho(client);
		}
	}

	@Test
	public void testRefusedWithoutPermission() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertTrue(client.executeCommand(ServerTestSupport.LOGIN, "bob", "bobpw").isPositive());
			ICommandResponse resp = client.executeCommand(ServerTestSupport.SECRET);
			assertEquals(500, resp.getResponseCode());
			assertTrue(resp.getResponseText().contains("not authorized"), resp.toString());
		}
	}

	@Test
	public void testAllowedWithPermission() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertTrue(client.executeCommand(ServerTestSupport.LOGIN, "alice", "alicepw").isPositive());
			ICommandResponse resp = client.executeCommand(ServerTestSupport.SECRET);
			assertTrue(resp.isPositive(), resp.toString());
			assertEquals("the secret", resp.getResponseText());
		}
	}

	@Test
	public void testBadLogins() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEquals(400, client.executeCommand(ServerTestSupport.LOGIN, "alice", "wrong").getResponseCode());
			assertEquals(400, client.executeCommand(ServerTestSupport.LOGIN, "nobody", "alicepw").getResponseCode());
			assertEquals(500, client.executeCommand(ServerTestSupport.LOGIN, "alice").getResponseCode());
			// A failed login leaves the session logged out
			assertEquals(500, client.executeCommand(ServerTestSupport.SECRET).getResponseCode());
		}
	}

	@Test
	public void testDisconnectRemovesClient() throws Exception {
		CommandClient client = ServerTestSupport.connect(svr);
		assertEcho(client);
		ServerTestSupport.waitFor(() -> !svr.getActiveClients().isEmpty(), 2000, "client was not registered");
		client.close();
		ServerTestSupport.waitFor(() -> svr.getActiveClients().isEmpty(), 5000, "server did not remove the closed client");
	}

	@Test
	public void testGreetingSentFirst() throws Exception {
		TestServer greeter = ServerTestSupport.create("GreetingServer", ServerTestSupport.standardCommands(), null);
		greeter.setServerGreating("220 welcome");
		ServerTestSupport.start(greeter);
		try (CommandClient client = ServerTestSupport.connect(greeter)) {
			assertEquals("220 welcome", client.readLine());
			assertEcho(client);
		} finally {
			ServerTestSupport.stop(greeter);
		}
	}

	private static boolean bjlIoSupportsMaxLineLength() {
		try {
			Class.forName("us.bringardner.io.AbstractLineReader").getMethod("setMaxLineLength", int.class);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Test
	public void testLineTooLongEndsSession() throws Exception {
		assumeTrue(bjlIoSupportsMaxLineLength(), "bjl_io on the class path has no line length limit");
		int saved = Connection.getDefaultMaxLineLength();
		// Applies to connections created from now on, including the server side one for this client
		Connection.setDefaultMaxLineLength(100);
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			StringBuilder longLine = new StringBuilder();
			for (int idx = 0; idx < 500; idx++) {
				longLine.append('x');
			}
			try {
				ICommandResponse resp = client.executeCommand(ServerTestSupport.ECHO, longLine.toString());
				// The server closed the session, so no real reply
				assertTrue(resp.isError(), resp.toString());
			} catch (IOException expected) {
				// Also fine: closing with unread input makes the server's TCP stack reset the connection
			}
		} finally {
			Connection.setDefaultMaxLineLength(saved);
		}
	}

	@Test
	public void testLineUnderLimitAccepted() throws Exception {
		int saved = Connection.getDefaultMaxLineLength();
		Connection.setDefaultMaxLineLength(100);
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertEquals(100, client.getMaxLineLength());
			assertEcho(client);
		} finally {
			Connection.setDefaultMaxLineLength(saved);
		}
	}
}
