/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.net.framework.nio;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import us.bringardner.core.BaseThread;
import us.bringardner.io.IoUtils;
import us.bringardner.net.framework.IManagedThread;
import us.bringardner.net.framework.server.Server;

/**
 * One selector thread serving many connections: it reads, writes, finishes connects and
 * runs tasks other threads hand it with {@link #execute(Runnable)}. A selector and its keys
 * are only touched by this thread.
 * <p>
 * A reactor can't be restarted once stopped; stopping it closes its connections.
 *
 * @author Tony Bringardner
 */
public class NioReactor extends BaseThread implements IManagedThread {

	/** How often idle connections are looked for (ms) */
	public static final long DEFAULT_IDLE_CHECK_INTERVAL = 1000;

	private final Selector selector;
	private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
	private volatile Thread reactorThread;
	private volatile long idleCheckInterval = DEFAULT_IDLE_CHECK_INTERVAL;

	public NioReactor(String name) throws IOException {
		super(name, true);
		getLogger().setLevel(Server.getDefaultLogLevel());
		selector = Selector.open();
	}

	/**
	 * @return true if the caller is this reactor's thread
	 */
	public boolean inReactorThread() {
		return Thread.currentThread() == reactorThread;
	}

	/**
	 * Run a task on the reactor thread, after the task already queued.
	 * Tasks must not block.
	 */
	public void execute(Runnable task) {
		tasks.add(task);
		if( !inReactorThread() ) {
			wakeup();
		}
	}

	void wakeup() {
		try {
			selector.wakeup();
		} catch (ClosedSelectorException e) {
			// stopped
		}
	}

	public long getIdleCheckInterval() {
		return idleCheckInterval;
	}

	public void setIdleCheckInterval(long milliSeconds) {
		this.idleCheckInterval = Math.max(10, milliSeconds);
	}

	/**
	 * Register a connection's channel with this reactor.
	 *
	 * @param conn the connection
	 * @param connected true if the channel is connected, false to wait for OP_CONNECT
	 */
	void register(NioConnection conn, boolean connected) {
		execute(() -> {
			if( !conn.isOpen() ) {
				return;
			}
			if( stopping ) {
				conn.close();
				return;
			}
			try {
				SelectionKey key = conn.getChannel().register(selector, connected ? 0 : SelectionKey.OP_CONNECT, conn);
				conn.attach(key);
				if( connected ) {
					conn.fireConnected();
				}
			} catch (IOException | RuntimeException e) {
				conn.fail(e);
			}
		});
	}

	/**
	 * Stop, the thread closes the connections and the selector as it exits.
	 */
	@Override
	public void stop() {
		super.stop();
		wakeup();
	}

	@Override
	public void run() {
		reactorThread = Thread.currentThread();
		started = running = true;
		logDebug(() -> "Reactor "+getName()+" started");
		long nextIdleCheck = System.currentTimeMillis()+idleCheckInterval;
		try {
			while( !stopping ) {
				runTasks();
				long wait = nextIdleCheck-System.currentTimeMillis();
				if( wait > 0 && tasks.isEmpty() ) {
					selector.select(wait);
				} else {
					selector.selectNow();
				}
				Iterator<SelectionKey> it = selector.selectedKeys().iterator();
				while( it.hasNext() ) {
					SelectionKey key = it.next();
					it.remove();
					handle(key);
				}
				long now = System.currentTimeMillis();
				if( now >= nextIdleCheck ) {
					checkIdle(now);
					nextIdleCheck = now+idleCheckInterval;
				}
			}
		} catch (IOException | ClosedSelectorException e) {
			if( !stopping ) {
				logError("Reactor "+getName()+" failed", e);
			}
		} catch (RuntimeException | Error e) {
			logError("Reactor "+getName()+" failed", e);
		} finally {
			shutdown();
			running = false;
			logDebug(() -> "Reactor "+getName()+" stopped");
		}
	}

	private void runTasks() {
		Runnable task;
		while( (task = tasks.poll()) != null ) {
			try {
				task.run();
			} catch (RuntimeException e) {
				logError("Error in reactor task", e);
			}
		}
	}

	private void handle(SelectionKey key) {
		Object att = key.attachment();
		if( !(att instanceof NioConnection) ) {
			return;
		}
		NioConnection conn = (NioConnection) att;
		try {
			if( key.isValid() && key.isConnectable() ) {
				conn.handleConnect();
			}
			if( key.isValid() && key.isReadable() ) {
				conn.handleRead();
			}
			if( key.isValid() && key.isWritable() ) {
				conn.handleWrite();
			}
		} catch (CancelledKeyException e) {
			// closed by another thread
		} catch (Throwable e) {
			conn.fail(e);
		}
	}

	private void checkIdle(long now) {
		for (SelectionKey key : selector.keys()) {
			Object att = key.attachment();
			if( key.isValid() && att instanceof NioConnection ) {
				((NioConnection) att).checkIdle(now);
			}
		}
	}

	private void shutdown() {
		List<NioConnection> open = new ArrayList<NioConnection>();
		try {
			for (SelectionKey key : selector.keys()) {
				if( key.attachment() instanceof NioConnection ) {
					open.add((NioConnection) key.attachment());
				}
			}
		} catch (ClosedSelectorException e) {
			// already closed
		}
		for (NioConnection conn : open) {
			conn.close();
		}
		// Connections registered after the loop ended (their register task sees them closed, or fails)
		runTasks();
		IoUtils.closeQuietly(selector);
		// Anything queued while closing (e.g. a flush) has nothing left to do
		tasks.clear();
	}
}
