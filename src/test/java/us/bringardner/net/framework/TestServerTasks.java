package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import us.bringardner.core.BaseThread;
import us.bringardner.net.framework.ServerTestSupport.TestServer;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;
import us.bringardner.net.framework.server.ICommand;
import us.bringardner.net.framework.server.Server.VirtualThreads;

/**
 * BJL-59: the server manages the threads a session starts (startTask) and runs scheduled work
 * on its own scheduler thread, created and shut down with the server.
 */
public class TestServerTasks {

	private static final String TASK = "Task";
	private static final String PROC = "Proc";

	/** Runs until released, stopped or interrupted. */
	static final class TestTask extends BaseThread {
		final CountDownLatch release = new CountDownLatch(1);
		volatile boolean stopCalled;
		volatile boolean interrupted;
		volatile boolean ranVirtual;

		@Override
		public void run() {
			started = running = true;
			ranVirtual = isVirtualThread();
			try {
				while (!stopping) {
					if (release.await(10, TimeUnit.MILLISECONDS)) {
						break;
					}
				}
			} catch (InterruptedException e) {
				interrupted = true;
			}
			running = false;
		}

		@Override
		public void stop() {
			stopCalled = true;
			super.stop();
		}
	}

	private static final Map<String, TestTask> TASKS = new ConcurrentHashMap<>();
	private static final Map<String, IProcessor> PROCESSORS = new ConcurrentHashMap<>();
	private static final AtomicInteger IDS = new AtomicInteger();

	private static TestServer server(String name) {
		Map<String, ICommand> commands = ServerTestSupport.standardCommands();
		commands.put(TASK, ServerTestSupport.command(TASK, false, (p, c) -> {
			TestTask t = new TestTask();
			p.getServer().startTask((IProcessor) p, t);
			String id = "t" + IDS.incrementAndGet();
			TASKS.put(id, t);
			p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK, id);
		}));
		commands.put(PROC, ServerTestSupport.command(PROC, false, (p, c) -> {
			String id = "p" + IDS.incrementAndGet();
			PROCESSORS.put(id, (IProcessor) p);
			p.reply(IGenericResponseCode.REPLY_200_GENERIC_OK, id);
		}));
		return ServerTestSupport.create(name, commands, null);
	}

	private static String id(ICommandResponse resp) {
		assertTrue(resp.isPositive(), resp.toString());
		return resp.getResponseText().trim();
	}

	private static TestTask startTask(CommandClient client) throws Exception {
		TestTask t = TASKS.get(id(client.executeCommand(TASK)));
		assertNotNull(t);
		ServerTestSupport.waitFor(t::isRunning, 5000, "task did not start");
		return t;
	}

	private static boolean schedulerThreadExists(String serverName) {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.getName().equals(serverName + "-scheduler") && t.isAlive()) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void taskRunsOnTheSessionsKindOfThread() throws Exception {
		TestServer svr = ServerTestSupport.start(server("TaskKind"));
		try {
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				TestTask t = startTask(client);
				assertFalse(t.ranVirtual, "OFF: platform thread");
				assertTrue(t.getName().contains("-task-"), t.getName());
				t.release.countDown();
			}
			svr.setVirtualThreads(VirtualThreads.ON);
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				TestTask t = startTask(client);
				assertEquals(BaseThread.isVirtualSupported(), t.ranVirtual, "ON: virtual where supported");
				t.release.countDown();
			}
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void taskIsStoppedWhenItsSessionEnds() throws Exception {
		TestServer svr = ServerTestSupport.start(server("TaskSessionEnd"));
		try {
			TestTask t;
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				t = startTask(client);
				assertEquals(1, svr.getTaskCount());
			}
			ServerTestSupport.waitFor(() -> !t.isAlive(), 5000, "task still running after its session ended");
			assertTrue(t.stopCalled, "stop() is called");
			assertEquals(0, svr.getTaskCount());
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void endedSessionCantStartTasks() throws Exception {
		TestServer svr = ServerTestSupport.start(server("TaskEnded"));
		try {
			IProcessor proc;
			try (CommandClient client = ServerTestSupport.connect(svr)) {
				proc = PROCESSORS.get(id(client.executeCommand(PROC)));
			}
			ServerTestSupport.waitFor(() -> svr.getActiveClients().isEmpty(), 5000, "session not removed");
			TestTask t = new TestTask();
			assertThrows(IllegalStateException.class, () -> svr.startTask(proc, t));
			assertFalse(t.isAlive());
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void stopStopsTasksAndTheScheduler() throws Exception {
		TestServer svr = server("TaskServerStop");
		assertThrows(IllegalStateException.class, () -> svr.schedule(() -> { }, 1, TimeUnit.MILLISECONDS), "not running");
		assertFalse(schedulerThreadExists("TaskServerStop"));
		ServerTestSupport.start(svr);
		CommandClient client = ServerTestSupport.connect(svr);
		try {
			TestTask t = startTask(client);
			assertTrue(schedulerThreadExists("TaskServerStop"));
			ServerTestSupport.stop(svr);
			assertFalse(t.isAlive(), "the server waits for its tasks");
			assertTrue(t.stopCalled);
			ServerTestSupport.waitFor(() -> !schedulerThreadExists("TaskServerStop"), 5000, "scheduler thread still running");
			assertThrows(IllegalStateException.class, () -> svr.schedule(() -> { }, 1, TimeUnit.MILLISECONDS));

			// A restart gets a new scheduler
			ServerTestSupport.start(svr);
			assertTrue(schedulerThreadExists("TaskServerStop"));
		} finally {
			client.close();
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void scheduledWorkRunsAndSurvivesExceptions() throws Exception {
		TestServer svr = ServerTestSupport.start(server("TaskSchedule"));
		try {
			CountDownLatch once = new CountDownLatch(1);
			AtomicReference<String> thread = new AtomicReference<>();
			svr.schedule(() -> {
				thread.set(Thread.currentThread().getName());
				once.countDown();
			}, 10, TimeUnit.MILLISECONDS);
			assertTrue(once.await(5, TimeUnit.SECONDS));
			assertEquals("TaskSchedule-scheduler", thread.get());

			AtomicInteger runs = new AtomicInteger();
			ScheduledFuture<?> f = svr.scheduleAtFixedRate(() -> {
				if (runs.incrementAndGet() == 1) {
					throw new IllegalStateException("first run fails");
				}
			}, 0, 10, TimeUnit.MILLISECONDS);
			ServerTestSupport.waitFor(() -> runs.get() >= 3, 5000, "a failed run cancelled the periodic task");
			f.cancel(false);
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void idleCheckKeepsSessionsWithARunningTask() throws Exception {
		TestServer svr = ServerTestSupport.start(server("TaskIdle"));
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			TestTask t = startTask(client);
			svr.setMaxIdleConnection(50);
			Thread.sleep(200);
			svr.runAdmin();
			assertEquals(1, svr.getActiveClients().size(), "a session with a running task isn't idle");
			t.release.countDown();
			ServerTestSupport.waitFor(() -> !t.isAlive(), 5000, "task did not end");
			svr.runAdmin();
			ServerTestSupport.waitFor(() -> svr.getActiveClients().isEmpty(), 5000, "idle session not closed");
		} finally {
			ServerTestSupport.stop(svr);
		}
	}

	@Test
	public void idleCheckRunsOnTheScheduler() throws Exception {
		TestServer svr = ServerTestSupport.start(server("TaskAdmin"));
		try (CommandClient client = ServerTestSupport.connect(svr)) {
			assertTrue(client.executeCommand(ServerTestSupport.ECHO, "hi").isPositive());
			svr.setMaxIdleConnection(100);
			svr.setAdminFrequency(50);
			// no runAdmin(): the scheduler closes the idle session
			ServerTestSupport.waitFor(() -> svr.getActiveClients().isEmpty(), 5000, "idle session not closed by the scheduled check");
		} finally {
			ServerTestSupport.stop(svr);
		}
	}
}
