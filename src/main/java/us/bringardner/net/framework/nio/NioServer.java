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
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.net.ssl.SSLEngine;

import us.bringardner.core.util.AbstractCoreServer;
import us.bringardner.io.IoUtils;
import us.bringardner.net.framework.IManagedThread;
import us.bringardner.net.framework.server.IAccessControlList;
import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IPrincipal;
import us.bringardner.net.framework.server.IServer;
import us.bringardner.net.framework.server.ImmutablePrincipal;
import us.bringardner.net.framework.server.Server;

/**
 * A non-blocking server, the NIO counterpart of the framework's Server.
 * <p>
 * The server thread accepts connections on a non-blocking ServerSocketChannel and hands
 * each one to one of a few {@link NioReactor}s (round robin). A reactor thread serves all
 * of its connections, so a few threads serve thousands of connections instead of one
 * thread per connection.
 * <p>
 * Each connection gets a handler from the {@link INioHandlerFactory} and a decoder from
 * the decoder factory. The handler calls run on the reactor thread, or on the
 * handler executor if one is set (use one when handlers block: authentication,
 * file I/O, starting processes...).
 * <p>
 * Like every BJL server it extends bjl_core's AbstractCoreServer, so the same properties 
 * configure it (Port, BindAddress, Backlog, KeepAlive, TcpNoDelay, MaxConnections, the key 
 * store settings of SecureBaseObject...), and the server attributes are shared by all connections.
 * <p>
 * TLS uses the server's SSLContext ({@code getSSLContext()}, from the key store settings): a 
 * secure server ({@code setSecure(true)} or the {@code secure} property) speaks TLS from the 
 * first byte, and any connection can switch with {@link INioConnection#startTls()}.
 *
 * <pre>
 * NioServer server = new NioServer(2222);
 * server.setHandlerFactory(() -&gt; new MyHandler());
 * server.setDecoderFactory(LineFrameDecoder::new);
 * server.startAndWait(5000);
 * </pre>
 *
 * @author Tony Bringardner
 */
public class NioServer extends AbstractCoreServer implements IManagedThread {

	/** Close connections idle for 24 hours, as the framework's Server does */
	public static final long DEFAULT_MAX_IDLE_TIME = 1000L*60*60*24;
	/** Property naming the IAccessControlList class, the same property as the framework's Server */
	public static final String AUTHENTICATION_PROVIDER_PROPERTY = IServer.AUTHENTICATION_PROVIDER_PROPERTY;
	public static final int DEFAULT_REACTOR_COUNT = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

	private boolean reuseAddress = true;
	// AbstractCoreServer defaults TCP_NODELAY to false; like the framework's Server this defaults to true
	private boolean tcpNoDelay = true;
	private int receiveBufferSize = 0;
	private int sendBufferSize = 0;
	private String serverBusyMessage;
	private long maxIdleTime = DEFAULT_MAX_IDLE_TIME;
	private int reactorCount = DEFAULT_REACTOR_COUNT;
	private int initialInputBuffer = NioConnection.DEFAULT_INITIAL_INPUT_BUFFER;
	private int maxInputBuffer = NioConnection.DEFAULT_MAX_INPUT_BUFFER;
	private volatile INioHandlerFactory handlerFactory;
	private volatile Supplier<IFrameDecoder> decoderFactory = RawFrameDecoder::new;
	private volatile Executor handlerExecutor;

	private final java.util.Map<Long, NioConnection> connections = new ConcurrentHashMap<Long, NioConnection>();

	private volatile Selector acceptSelector;
	private volatile ServerSocketChannel serverChannel;
	private volatile NioReactor[] reactors;
	private final AtomicInteger nextReactor = new AtomicInteger();
	private volatile IAccessControlList accessControl;
	// True once the provider property has been looked at and named no provider
	private volatile boolean noAccessControl;
	// Why the last start() failed (null if it started or is still starting)
	private volatile Throwable startupError;

	public NioServer() {
		getLogger().setLevel(Server.getDefaultLogLevel());
	}

	public NioServer(int port) {
		this();
		setPort(port);
	}

	public NioServer(int port, String name) {
		this(port);
		setName(name);
	}

	public NioServer(int port, INioHandlerFactory handlerFactory) {
		this(port);
		setHandlerFactory(handlerFactory);
	}

	// ------------------------------------------------------------------ life cycle

	/**
	 * Clears any previous startup error, see {@link #startAndWait(long)}.
	 */
	@Override
	public synchronized void start() {
		startupError = null;
		super.start();
	}

	/**
	 * Start the server and wait until it is accepting connections.
	 * Unlike start(), a server that can't start (port in use, no handler factory...)
	 * is reported to the caller instead of only being logged.
	 *
	 * @throws IOException with the reason if the server did not start within the timeout
	 */
	public void startAndWait(long timeoutMillis) throws IOException {
		start();
		long end = System.currentTimeMillis() + timeoutMillis;
		while( !isRunning() ) {
			Throwable error = startupError;
			if( error != null ) {
				if( error instanceof IOException ) {
					throw (IOException) error;
				}
				throw new IOException("Server "+getName()+" failed to start: "+error.getMessage(), error);
			}
			if( System.currentTimeMillis() > end ) {
				throw new IOException("Server "+getName()+" did not start within "+timeoutMillis+" ms");
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted waiting for server "+getName()+" to start");
			}
		}
	}

	/**
	 * @return why the last start() failed, or null if it started (or is still starting).
	 */
	public Throwable getStartupError() {
		return startupError;
	}

	/**
	 * Stop accepting, close every connection and stop the reactors.
	 * Returns at once, the server thread does the work.
	 */
	@Override
	public void stop() {
		// AbstractCoreServer's stop() also closes its (unused) blocking ServerSocket
		super.stop();
		Selector s = acceptSelector;
		if( s != null ) {
			s.wakeup();
		}
	}

	@Override
	public void run() {
		if( handlerFactory == null ) {
			logError("No handler factory defined.");
			startupError = new IllegalStateException("No handler factory defined.");
			return;
		}

		try {
			open();
		} catch (IOException | RuntimeException e) {
			// e.g. port in use
			logError("Can't open server "+getName()+" on port "+getPort(), e);
			startupError = e;
			cleanup();
			return;
		}

		started = running = true;
		logInfo("Server "+getName()+" is running on port "+getLocalPort()+".");
		try {
			while( !stopping ) {
				acceptSelector.select();
				Iterator<SelectionKey> it = acceptSelector.selectedKeys().iterator();
				while( it.hasNext() ) {
					SelectionKey key = it.next();
					it.remove();
					if( key.isValid() && key.isAcceptable() ) {
						acceptAll();
					}
				}
			}
		} catch (IOException | ClosedSelectorException e) {
			if( !stopping ) {
				logError("Error in server "+getName(), e);
			}
		} catch (RuntimeException | Error e) {
			logError("Error in server "+getName(), e);
		} finally {
			cleanup();
			running = false;
			logInfo("Server "+getName()+" has stopped.");
		}
	}

	private void open() throws IOException {
		if( isSecure() ) {
			// A missing key store or wrong password is a startup error, not a failed handshake later
			getSSLContext();
		}
		acceptSelector = Selector.open();
		ServerSocketChannel ch = ServerSocketChannel.open();
		serverChannel = ch;
		ch.configureBlocking(false);
		// set before binding so a restart doesn't fail while the old port is in TIME_WAIT
		ch.setOption(StandardSocketOptions.SO_REUSEADDR, reuseAddress);
		if( receiveBufferSize > 0 ) {
			// set before binding so it applies to the window of accepted sockets
			ch.setOption(StandardSocketOptions.SO_RCVBUF, receiveBufferSize);
		}
		ch.bind(new InetSocketAddress(getBindAddr(), getPort()), getBacklog());
		ch.register(acceptSelector, SelectionKey.OP_ACCEPT);

		int count = Math.max(1, reactorCount);
		NioReactor[] rs = new NioReactor[count];
		reactors = rs;
		String name = getName();
		for (int i = 0; i < count; i++) {
			rs[i] = new NioReactor(name+"-reactor-"+i);
			rs[i].start();
		}
	}

	private void cleanup() {
		ServerSocketChannel ch = serverChannel;
		serverChannel = null;
		IoUtils.closeQuietly(ch);
		Selector s = acceptSelector;
		acceptSelector = null;
		IoUtils.closeQuietly(s);
		for (NioConnection conn : new ArrayList<NioConnection>(connections.values())) {
			conn.close();
		}
		connections.clear();
		NioReactor[] rs = reactors;
		reactors = null;
		if( rs != null ) {
			for (NioReactor r : rs) {
				if( r != null ) {
					r.stop();
				}
			}
			for (NioReactor r : rs) {
				try {
					if( r != null ) {
						r.join(5000);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

	private void acceptAll() throws IOException {
		ServerSocketChannel ch = serverChannel;
		SocketChannel sc;
		while( ch != null && (sc = ch.accept()) != null ) {
			handleNewConnection(sc);
		}
	}

	/**
	 * Hand a newly accepted channel to a reactor. Runs on the accept thread so it must 
	 * not block and must never throw. If the hand off fails the channel is closed.
	 */
	protected void handleNewConnection(SocketChannel sc) {
		boolean acquired = false;
		NioConnection conn = null;
		try {
			sc.configureBlocking(false);
			if( !tryAcquireConnection() ) {
				logInfo("Rejecting connection from "+sc.getRemoteAddress()+", "+getActiveConnections()+" clients active (max="+getMaxConnections()+")");
				rejectBusy(sc);
				IoUtils.closeQuietly(sc);
				return;
			}
			acquired = true;
			sc.setOption(StandardSocketOptions.TCP_NODELAY, isTcpNoDelay());
			sc.setOption(StandardSocketOptions.SO_KEEPALIVE, isKeepAlive());
			if( sendBufferSize > 0 ) {
				sc.setOption(StandardSocketOptions.SO_SNDBUF, sendBufferSize);
			}

			NioReactor reactor = nextReactor();
			INioHandler handler = handlerFactory.getHandler();
			if( handler == null ) {
				throw new IllegalStateException("The handler factory returned null");
			}
			conn = new NioConnection(sc, reactor, this, handler, decoderFactory.get(), 
					handlerExecutor, initialInputBuffer, maxInputBuffer, maxIdleTime, this::createSSLEngine, isSecure());
			connections.put(conn.getId(), conn);
			NioConnection accepted = conn;
			logDebug(() -> "Accepted "+accepted);
			reactor.register(conn, true);
		} catch (Exception e) {
			logError("Can't start connection for "+sc, e);
			if( conn != null ) {
				// releases the connection through connectionClosed()
				conn.close();
			} else {
				if( acquired ) {
					releaseConnection();
				}
				IoUtils.closeQuietly(sc);
			}
		}
	}

	/**
	 * Make the SSLEngine for a connection, from the server's SSLContext. Override to change 
	 * the protocols, cipher suites or client certificate settings.
	 * 
	 * @throws IOException if the SSLContext can't be created (no key store...)
	 */
	protected SSLEngine createSSLEngine(INioConnection connection) throws IOException {
		return TlsLayer.serverEngine(getSSLContext(), isNeedClientAuth());
	}

	/**
	 * Called when max clients has been reached, the channel is closed by the caller.
	 * The busy message is sent with one non-blocking write (it fits an empty socket buffer).
	 */
	protected void rejectBusy(SocketChannel sc) {
		String msg = serverBusyMessage;
		if( msg != null && !msg.isEmpty() ) {
			try {
				sc.write(ByteBuffer.wrap((msg+"\r\n").getBytes(StandardCharsets.UTF_8)));
			} catch (IOException e) {
				// Ignore, we're closing it anyway
			}
		}
	}

	private NioReactor nextReactor() {
		NioReactor[] rs = reactors;
		if( rs == null ) {
			throw new IllegalStateException("Server "+getName()+" is not running");
		}
		return rs[Math.floorMod(nextReactor.getAndIncrement(), rs.length)];
	}

	/**
	 * Called by a connection as it closes (once per connection).
	 */
	@Override
	public void connectionClosed(Object connectionObject) {
		if( connectionObject instanceof NioConnection ) {
			NioConnection conn = (NioConnection) connectionObject;
			if( connections.remove(conn.getId(), conn) ) {
				releaseConnection();
			}
		}
	}

	// ------------------------------------------------------------------ properties

	/**
	 * @return the port the server is listening on (useful when the port was 0), or -1 if not bound.
	 */
	public int getLocalPort() {
		ServerSocketChannel ch = serverChannel;
		if( ch != null ) {
			try {
				SocketAddress addr = ch.getLocalAddress();
				if( addr instanceof InetSocketAddress ) {
					return ((InetSocketAddress) addr).getPort();
				}
			} catch (IOException e) {
				// closed
			}
		}
		return -1;
	}

	/**
	 * @return the open connections (a read only view)
	 */
	public Collection<INioConnection> getConnections() {
		return Collections.unmodifiableCollection(connections.values());
	}

	public int getConnectionCount() {
		return connections.size();
	}

	public boolean isReuseAddress() {
		return reuseAddress;
	}

	/**
	 * Set SO_REUSEADDR on the server socket (default true).
	 */
	public void setReuseAddress(boolean reuseAddress) {
		this.reuseAddress = reuseAddress;
	}

	//  Overrides AbstractCoreServer (which defaults to false): this server defaults to true
	@Override
	public boolean isTcpNoDelay() {
		return tcpNoDelay;
	}

	/**
	 * Set TCP_NODELAY on accepted sockets (default true).
	 */
	@Override
	public void setTcpNoDelay(boolean tcpNoDelay) {
		this.tcpNoDelay = tcpNoDelay;
	}

	public int getReceiveBufferSize() {
		return receiveBufferSize;
	}

	/**
	 * SO_RCVBUF for accepted sockets, 0 = OS default. Applies from the next start.
	 */
	public void setReceiveBufferSize(int receiveBufferSize) {
		this.receiveBufferSize = receiveBufferSize;
	}

	public int getSendBufferSize() {
		return sendBufferSize;
	}

	/**
	 * SO_SNDBUF for accepted sockets, 0 = OS default.
	 */
	public void setSendBufferSize(int sendBufferSize) {
		this.sendBufferSize = sendBufferSize;
	}

	public String getServerBusyMessage() {
		return serverBusyMessage;
	}

	/**
	 * @param serverBusyMessage optional line sent to clients rejected because {@link #getMaxConnections()} are open
	 */
	public void setServerBusyMessage(String serverBusyMessage) {
		this.serverBusyMessage = serverBusyMessage;
	}

	public long getMaxIdleTime() {
		return maxIdleTime;
	}

	/**
	 * @param milliSeconds time without reads or writes before the handler's onIdle
	 * (which closes the connection by default), 0 = never. Applies to new connections.
	 */
	public void setMaxIdleTime(long milliSeconds) {
		this.maxIdleTime = milliSeconds;
	}

	public int getReactorCount() {
		return reactorCount;
	}

	/**
	 * @param reactorCount number of selector threads, applies from the next start
	 */
	public void setReactorCount(int reactorCount) {
		this.reactorCount = reactorCount;
	}

	public int getInitialInputBuffer() {
		return initialInputBuffer;
	}

	public void setInitialInputBuffer(int size) {
		this.initialInputBuffer = size;
	}

	public int getMaxInputBuffer() {
		return maxInputBuffer;
	}

	/**
	 * @param size the largest input buffer of a connection; it must hold the largest frame
	 */
	public void setMaxInputBuffer(int size) {
		this.maxInputBuffer = size;
	}

	public INioHandlerFactory getHandlerFactory() {
		return handlerFactory;
	}

	public void setHandlerFactory(INioHandlerFactory handlerFactory) {
		this.handlerFactory = handlerFactory;
	}

	public Supplier<IFrameDecoder> getDecoderFactory() {
		return decoderFactory;
	}

	/**
	 * @param decoderFactory makes the decoder of each new connection (default {@link RawFrameDecoder})
	 */
	public void setDecoderFactory(Supplier<IFrameDecoder> decoderFactory) {
		this.decoderFactory = decoderFactory == null ? RawFrameDecoder::new : decoderFactory;
	}

	public Executor getHandlerExecutor() {
		return handlerExecutor;
	}

	/**
	 * @param handlerExecutor runs the handler calls of new connections (each connection's
	 * calls still one at a time, in order), null to run them on the reactor threads
	 */
	public void setHandlerExecutor(Executor handlerExecutor) {
		this.handlerExecutor = handlerExecutor;
	}

	// ------------------------------------------------------------------ access control

	/**
	 * Authenticate a user with the given credentials (probably a password), as the 
	 * framework's Server does.
	 * 
	 * @return a read only authenticated principal, or null if the user can't be authenticated 
	 * or there is no access control
	 */
	public IPrincipal authenticate(String user, byte[] credentials) {
		IPrincipal ret = null;
		IAccessControlList acl = getAccessControl();
		if( acl != null && user != null ) {
			IPrincipal tmp = acl.getPrincipal(user);
			if( tmp == null ) {
				// Same work as a real check so timing doesn't reveal valid user names
				acl.authenticateUnknownUser(credentials);
			} else if( tmp.authenticate(credentials) ) {
				ret = new ImmutablePrincipal(tmp);
				ret.setState(IPrincipal.State.Authenticated);
			}
		}
		return ret;
	}

	/**
	 * @return true if the user is logged in and the access control allows the action
	 */
	public boolean isAuthorized(IPrincipal user, IPermission action) {
		if( user == null ) {
			return false;
		}
		IAccessControlList acl = getAccessControl();
		return acl != null && acl.checkPermission(user, action);
	}

	/**
	 * @return the access control set with {@link #setAccessControl(IAccessControlList)}, else 
	 * one made from the {@value #AUTHENTICATION_PROVIDER_PROPERTY} property (initialized 
	 * with this server's name, e.g. FileBasedAcl or PropertyAuthenticator), else null
	 * @throws IllegalStateException if the property names a class that can't be set up
	 */
	public IAccessControlList getAccessControl() {
		if( accessControl == null && !noAccessControl ) {
			synchronized (this) {
				if( accessControl == null && !noAccessControl ) {
					String tmp = getProperty(AUTHENTICATION_PROVIDER_PROPERTY);
					if( tmp != null && !tmp.trim().isEmpty() ) {
						try {
							Class<?> authClass = Class.forName(tmp.trim());
							IAccessControlList auth = (IAccessControlList) authClass.getDeclaredConstructor().newInstance();
							auth.initialize(getName());
							accessControl = auth;
						} catch (Exception e) {
							logError("Can't configure access control class='"+tmp+"'", e);
							throw new IllegalStateException("Can't configure access control class='"+tmp+"'", e);
						}
					} else {
						noAccessControl = true;
						logInfo("No access control defined in server "+getName());
					}
				}
			}
		}
		return accessControl;
	}

	/**
	 * @param acl the access control, already initialized; null to look at the property again on the next use
	 */
	public void setAccessControl(IAccessControlList acl) {
		this.accessControl = acl;
		this.noAccessControl = false;
	}
}
