package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;

/**
 * CommandClient.executeCommands (BJL-43): commands sent together, replies read in order.
 */
public class TestPipelining {

	private static TestServer svr;

	@BeforeAll
	public static void startServer() {
		svr = ServerTestSupport.start(ServerTestSupport.create("PipelineServer", ServerTestSupport.standardCommands(), null));
	}

	@AfterAll
	public static void stopServer() {
		ServerTestSupport.stop(svr);
	}

	@Test
	public void repliesComeBackInOrder() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			List<ICommandResponse> replies = client.executeCommands(Arrays.asList(
					ServerTestSupport.ECHO + " one", "NoSuchCommand", ServerTestSupport.ECHO + " three"));
			assertEquals(3, replies.size());
			assertTrue(replies.get(0).isPositive());
			assertEquals("one", replies.get(0).getResponseText());
			assertFalse(replies.get(1).isPositive(), "an error reply stays with its command");
			assertEquals("three", replies.get(2).getResponseText());
			// the connection is in step afterwards
			assertEquals("after", client.executeCommand(ServerTestSupport.ECHO, "after").getResponseText());
		}
	}

	@Test
	public void longListsGoInBatches() throws Exception {
		int n = CommandClient.MAX_PIPELINED * 3 + 5;
		List<String> commands = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			commands.add(ServerTestSupport.ECHO + " msg" + i);
		}
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			List<ICommandResponse> replies = client.executeCommands(commands);
			assertEquals(n, replies.size());
			for (int i = 0; i < n; i++) {
				assertEquals("msg" + i, replies.get(i).getResponseText(), "reply " + i);
			}
		}
	}

	@Test
	public void emptyListDoesNothing() throws Exception {
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertTrue(client.executeCommands(new ArrayList<>()).isEmpty());
			assertEquals("still fine", client.executeCommand(ServerTestSupport.ECHO, "still fine").getResponseText());
		}
	}
}
