package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import us.bringardner.core.BaseThread;
import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;
import us.bringardner.net.framework.IGenericResponseCode;
import us.bringardner.net.framework.server.Server;
import us.bringardner.net.framework.server.Server.VirtualThreads;

/**
 * BJL-51: Server.setVirtualThreads(OFF | ON | AUTO) decides what runs each session. The tests
 * use bjl_core's multi-release jar, so on JDK 21+ ON gives virtual threads and on JDK 11 it
 * quietly stays on platform threads.
 */
public class TestVirtualThreads {

	private static final String THREAD = "Thread";

	private static TestServer server(String name) {
		Map<String, us.bringardner.net.framework.server.ICommand> commands = ServerTestSupport.standardCommands();
		commands.put(THREAD, ServerTestSupport.command(THREAD, false, (p, c) ->
			p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK,
					((BaseThread) p).isVirtualThread() ? "virtual" : "platform")));
		return ServerTestSupport.create(name, commands, null);
	}

	private static String sessionThread(CommandClient client) throws Exception {
		ICommandResponse resp = client.executeCommand(THREAD);
		assertTrue(resp.isPositive(), resp.toString());
		return resp.toString().contains("virtual") ? "virtual" : "platform";
	}

	@Test
	public void offByDefault() {
		Server svr = server("VtDefault");
		assertEquals(VirtualThreads.OFF, Server.DEFAULT_VIRTUAL_THREADS);
		assertEquals(VirtualThreads.OFF, svr.getVirtualThreads());
		assertFalse(svr.isUsingVirtualThreads());
	}

	@Test
	public void settingFollowsTheJvm() {
		Server svr = server("VtSetting");
		svr.setVirtualThreads(VirtualThreads.ON);
		assertEquals(BaseThread.isVirtualSupported(), svr.isUsingVirtualThreads());
		svr.setVirtualThreads(VirtualThreads.AUTO);
		assertEquals(BaseThread.isVirtualRecommended(), svr.isUsingVirtualThreads());
		assertEquals(Runtime.version().feature() >= 24, svr.isUsingVirtualThreads());
		svr.setVirtualThreads(VirtualThreads.OFF);
		assertFalse(svr.isUsingVirtualThreads());
	}

	@Test
	public void propertySetsIt() {
		Server svr = server("VtProperty");
		try {
			System.setProperty(Server.PROPERTY_VIRTUAL_THREADS, "on");
			svr.setVirtualThreads(null); // read the property again
			assertEquals(VirtualThreads.ON, svr.getVirtualThreads());
			System.setProperty(Server.PROPERTY_VIRTUAL_THREADS, " Auto ");
			svr.setVirtualThreads(null);
			assertEquals(VirtualThreads.AUTO, svr.getVirtualThreads());
			System.setProperty(Server.PROPERTY_VIRTUAL_THREADS, "nonsense");
			svr.setVirtualThreads(null);
			assertEquals(VirtualThreads.OFF, svr.getVirtualThreads(), "an invalid value falls back to the default");
		} finally {
			System.clearProperty(Server.PROPERTY_VIRTUAL_THREADS);
		}
	}

	@Test
	public void sessionsRunOnTheChosenThreads() throws Exception {
		TestServer svr = server("VtSessions");
		ServerTestSupport.start(svr);
		try {
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				assertEquals("platform", sessionThread(client), "OFF");
			}
			svr.setVirtualThreads(VirtualThreads.ON);
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				assertEquals(BaseThread.isVirtualSupported() ? "virtual" : "platform", sessionThread(client), "ON");
				ICommandResponse echo = client.executeCommand(ServerTestSupport.ECHO, "hi");
				assertTrue(echo.isPositive(), echo.toString());
			}
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	/**
	 * The point of it: many open sessions without a platform thread each. On Java 21-23 this
	 * stalled with bjl_io 1.0.0: every idle session pinned a carrier thread in the synchronized
	 * readLine, so the server stopped answering once there were as many sessions as CPUs (BJL-55).
	 */
	@Test
	public void manySessionsFewPlatformThreads() throws Exception {
		final int sessions = 300;
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		TestServer svr = server("VtMany");
		svr.setVirtualThreads(VirtualThreads.ON);
		ServerTestSupport.start(svr);
		List<CommandClient> clients = new ArrayList<>();
		try {
			int before = mx.getThreadCount();
			for (int i = 0; i < sessions; i++) {
				CommandClient c = ServerTestSupport.connect(svr);
				clients.add(c);
			}
			for (CommandClient c : clients) {
				assertTrue(c.executeCommand(ServerTestSupport.ECHO, "x").isPositive());
			}
			ServerTestSupport.waitFor(() -> svr.getActiveClients().size() == sessions, 5000, "sessions not active");
			int added = mx.getThreadCount() - before;
			if (BaseThread.isVirtualSupported()) {
				// Virtual threads aren't counted; only a few carrier threads may be added
				assertTrue(added < 100, sessions + " sessions added " + added + " platform threads");
			} else {
				assertTrue(added >= sessions, "one platform thread per session on this JDK, added " + added);
			}
		} finally {
			for (CommandClient c : clients) {
				c.close();
			}
			ServerTestSupport.stop(svr);
		}
	}
}
