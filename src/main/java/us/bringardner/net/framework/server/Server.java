/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 * ~version~V000.00.02-V000.00.01-V000.00.00-
 */
package us.bringardner.net.framework.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import us.bringardner.core.BaseThread;
import us.bringardner.core.ILogger.Level;
import us.bringardner.core.util.AbstractCoreServer;
import us.bringardner.net.framework.IConnection;
import us.bringardner.net.framework.IConnectionFactory;
import us.bringardner.net.framework.IProcessor;
import us.bringardner.net.framework.IProcessorFactory;
import us.bringardner.net.framework.server.IPrincipal.State;
import us.bringardner.core.NamedThreadFactory;
import us.bringardner.core.SecureBaseObject;
import us.bringardner.io.IoUtils;

public class Server extends AbstractCoreServer implements IServer {

	private static class ImmutablePrincipal implements IPrincipal {

		IPrincipal target ;
		State state = State.Authenticated;
		
		ImmutablePrincipal (IPrincipal tmp) {
			this.target = tmp;
		}


		@Override
		public boolean authenticate(byte[] credentials) {
			throw new UnsupportedOperationException("This is an imutable principal");
		}

		@Override
		public State getState() {
			return state;
		}

		@Override
		public void setState(State state) {
			this.state= state;	
		}

		@Override
		public byte[] getCredentials() {
			throw new UnsupportedOperationException("Credentials are not visible here");			
		}

		@Override
		public void setCredentials(byte[] credentials) {
			throw new UnsupportedOperationException("This is an imutable principal");			
		}

		@Override
		public void add(IPermission permission) {
			throw new UnsupportedOperationException("This is an imutable principal");			
		}

		@Override
		public boolean hasPermission(IPermission permision) {			
			return target.hasPermission(permision);
		}

		@Override
		public boolean remove(IPermission permission) {
			throw new UnsupportedOperationException("This is an imutable principal");
		}

		@Override
		public List<IPermission> getPermisssions() {			
			return Collections.unmodifiableList(target.getPermissions());
		}

		@Override
		public void setPermissions(List<IPermission> permissions) {
			throw new UnsupportedOperationException("This is an imutable principal");			
		}

		@Override
		public Object getParameter(Object key) {			
			return target.getParameter(key);
		}

		@Override
		public Object removeParameter(Object key) {
			throw new UnsupportedOperationException("This is an imutable principal");
		}

		@Override
		public void setParameter(Object key, Object value) {
			throw new UnsupportedOperationException("This is an imutable principal");			
		}

		@Override
		public Map<Object, Object> getParameters() {
			return Collections.unmodifiableMap(target.getParameters());
		}

		@Override
		public void setParameters(Map<Object, Object> parameters) {
			throw new UnsupportedOperationException("This is an imutable principal");

		}

		@Override
		public String getName() {			
			return target.getName();
		}

	}
	/**
	 * 
	 */
	protected static final long serialVersionUID = 1L;
	public static final int DEFAULT_ACCEPT_TIMEOUT = 5000;
	public static final int DEFAULT_CONNECTION_TIMEOUT = 60000;
	//  Max idle connection 24 hr
	public static final long DEFAULT_MAX_IDLE_CONNECTION = 1000*60*60*24;
	/** @deprecated misspelled, use {@link #DEFAULT_MAX_IDLE_CONNECTION} */
	@Deprecated
	public static final long DEFAULT_MAX_IDEL_CONNECTION = DEFAULT_MAX_IDLE_CONNECTION;
	//  Default admin freq = 5min
	private static final long DEFAULT_ADMIN_REFQ = 1000*60*5;
	//  Max concurrent clients, 0 = unlimited
	public static final int DEFAULT_MAX_CLIENTS = 0;

	private static int defaultAcceptTimeout = DEFAULT_ACCEPT_TIMEOUT;
	private static int defaultConnectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	private static Level defaultLogLevel = Level.NONE;

	private int port;
	private boolean secure = false;
	private volatile ServerSocketFactory serverSocketFactory;
	private IProcessorFactory processorFactory;

	private IConnectionFactory connectionFactory;
	private int acceptTimeout     = getDefaultAcceptTimeout();
	private long maxIdleConnection = DEFAULT_MAX_IDLE_CONNECTION;
	private volatile long adminFreq = DEFAULT_ADMIN_REFQ;
	private volatile long lastAdmin=0;

	// Shared by all processor threads
	private volatile Map<String,Object> runtimeValues = new ConcurrentHashMap<String, Object>();
	// Used by the accept thread, every processor thread and doAdmin(), so it must be thread safe.
	// Entries are removed explicitly in removeClient() / doAdmin(), so weak keys are not needed.
	private final Map<Socket, IProcessor> activeClients = new ConcurrentHashMap<Socket, IProcessor>();

	/**
	 * Threads started for a session with {@link #startTask(IProcessor, BaseThread)}, by session
	 * (BJL-59). A session's entry exists from the time it is accepted until removeClient().
	 */
	private final Map<IProcessor, java.util.Set<BaseThread>> sessionTasks = new ConcurrentHashMap<IProcessor, java.util.Set<BaseThread>>();
	private final AtomicInteger taskNumber = new AtomicInteger();
	/** How long the stopping server waits for session tasks to end, see {@link #setTaskStopWait(long)}. */
	public static final long DEFAULT_TASK_STOP_WAIT = 5000;
	private volatile long taskStopWait = DEFAULT_TASK_STOP_WAIT;
	/** One daemon platform thread per running server for scheduled work (BJL-59). */
	private volatile ScheduledThreadPoolExecutor scheduler;
	private volatile ScheduledFuture<?> adminTask;
	private int connectionTimeout = getDefaultConnectionTimeout();
	private int maxClients = DEFAULT_MAX_CLIENTS;
	private String serverBusyMessage;
	private volatile ServerSocket svr = null;
	// Why the last start() failed (null if it started or is still starting)
	private volatile Throwable startupError;
	private boolean tcpNoDelay = true;
	private boolean reuseAddress = true;
	private int backlog = 0;
	private boolean debug;
	private volatile IAccessControlList accessControl;
	/**
	 * True once the access control property has been looked at and named no provider, so
	 * every command doesn't take the server lock, look the property up again and log
	 * "No access control defined" (BJL-42). Cleared by setAccessControl.
	 */
	private volatile boolean noAccessControl;
	private String serverGreating;

	/**
	 * Which threads run the sessions (BJL-51). OFF: platform threads, as before. ON: virtual
	 * threads where the JVM has them (Java 21+), platform threads otherwise. AUTO: virtual
	 * threads on Java 24 and later only ({@link BaseThread#isVirtualRecommended()}).
	 * <p>
	 * ON is fine on Java 21-23 with bjl_io 1.0.1 or later: sessions wait for commands in a
	 * line reader that uses a lock. (bjl_io 1.0.0's reader was synchronized, and on 21-23 a
	 * virtual thread blocked inside a monitor pins its carrier thread, so the server stopped
	 * answering once there were as many idle sessions as CPUs; BJL-55.) Commands that block on
	 * I/O inside their own synchronized code still pin a carrier thread while they do on 21-23;
	 * Java 24 fixed that (JEP 491), which is why AUTO starts at 24.
	 */
	public enum VirtualThreads { OFF, ON, AUTO }

	/** Property for {@link #setVirtualThreads(VirtualThreads)}: OFF, ON or AUTO (any case). */
	public static final String PROPERTY_VIRTUAL_THREADS = "VirtualThreads";
	/** The default, so upgrading the framework doesn't change how sessions run. */
	public static final VirtualThreads DEFAULT_VIRTUAL_THREADS = VirtualThreads.OFF;
	private volatile VirtualThreads virtualThreads;



	public static Level getDefaultLogLevel() {
		return defaultLogLevel;
	}

	public static void setDefaultLogLevel(Level level) {
		defaultLogLevel = level;
	}

	/**
	 * @return the line sent to each client when it connects, or null for none
	 */
	public String getServerGreeting() {
		return serverGreating;
	}

	/**
	 * @param serverGreeting line sent to each client when it connects (e.g. "220 ready"), null for none
	 */
	public void setServerGreeting(String serverGreeting) {
		this.serverGreating = serverGreeting;
	}

	/** @deprecated misspelled, use {@link #getServerGreeting()} */
	@Deprecated
	public String getServerGreating() {
		return serverGreating;
	}

	/** @deprecated misspelled, use {@link #setServerGreeting(String)} */
	@Deprecated
	public void setServerGreating(String serverGreating) {
		this.serverGreating = serverGreating;
	}

	public Server() {
		getLogger().setLevel(defaultLogLevel);
	}
	public Server(int port) {
		this();
		setPort(port);
	}


	public Server(int port, String name) {
		this(port);
		setName(name);
	}



	public static int getDefaultAcceptTimeout() {
		return defaultAcceptTimeout;
	}

	public static void setDefaultAcceptTimeout(int defaultAcceptTimeout) {
		Server.defaultAcceptTimeout = defaultAcceptTimeout;
	}

	public static int getDefaultConnectionTimeout() {
		return defaultConnectionTimeout;
	}

	public static void setDefaultConnectionTimeout(int defaultConnectionTimeout) {
		Server.defaultConnectionTimeout = defaultConnectionTimeout;
	}


	/**
	 * @throws IllegalStateException if a secure factory is requested and the SSL context can't be created
	 * (previously null was returned and the cause only logged).
	 */
	public  SocketFactory getSocketFactory(boolean isSecure) {
		SocketFactory ret = null;

		if (isSecure) {
			try {
				// set up key manager to do server authentication
				ret = getSSLContext().getSocketFactory();
			} catch (Exception e) {
				throw new IllegalStateException("Can't create SSL socket factory for server "+getName()+": "+e.getMessage(), e);
			}
		} else {
			ret =  SocketFactory.getDefault();
		}

		return ret;

	}

	/** Names a client may use to ask for TLS: TLS, SSL, TLS-C, TLS-P, TLSv1.3, SSLv3... (any case) */
	private static final Pattern TLS_MECHANISM = Pattern.compile("(?i)(TLS|SSL)([-_V].*)?");

	/**
	 * The TLS context for a STARTTLS / AUTH style upgrade of a connection (BJL-38).
	 * <p>
	 * The name the client sends (AUTH TLS, AUTH SSL, TLS-C, STARTTLS...) means "start TLS"; it
	 * does not choose a TLS version. A JSSE server context accepts the same versions whatever
	 * name it was created with, so every upgrade uses the server's one context
	 * ({@link #getSSLContext()}). That keeps a single session cache, so clients can resume
	 * sessions across connections (and FTP data connections can resume the control session).
	 * <p>
	 * This used to switch the server's protocol to the requested name, build a context and switch
	 * back: a new context for every upgrade, the server's main context discarded too, and another
	 * thread could get the other protocol's context during the switch. To limit the TLS versions
	 * use the socket's enabled protocols (e.g. {@code SecureBaseObject.PROPERTY_FORCE_TLS_VERSION}),
	 * not the context name.
	 *
	 * @param sslOrTls the mechanism the client asked for: null, TLS, SSL or a name starting with
	 * TLS or SSL (TLS-C, TLS-P, TLSv1.3...)
	 * @return the server's TLS context
	 * @throws IOException if the name is not a TLS/SSL mechanism or the context can't be created
	 */
	public SSLContext getSSLContext(String sslOrTls) throws IOException {
		if( sslOrTls != null && !TLS_MECHANISM.matcher(sslOrTls.trim()).matches() ) {
			throw new IOException("Unsupported security mechanism: "+sslOrTls);
		}
		return getSSLContext();
	}



	/**
	 * Build a TLS context from a key store file, separately from this server's own settings.
	 *
	 * @param sslOrTls the SSLContext protocol, e.g. "TLS"
	 * @param instanceName the key manager algorithm, e.g. "SunX509" or "PKIX"
	 * @param keyStoreType e.g. "PKCS12"
	 * @param passPhrase the key store (and key) password
	 * @param keyFileName the key store file
	 * @return a new SSLContext with the key store's keys and the JVM's default trust managers
	 * @throws IOException if the file is missing, the password is wrong or the context can't be made
	 * @deprecated set the key store on the server instead ({@code setKeyStoreFileName},
	 *  {@code setKeyStorePassword}, {@code setKeyStoreType}, {@code setAlgorithm}, {@code setProtocol})
	 *  and use {@link #getSSLContext()}, or configure a bjl_core SecureBaseObject. This now does
	 *  exactly that instead of loading the key store with its own code.
	 */
	@Deprecated
	public SSLContext getSSLContext(String sslOrTls, String instanceName, String keyStoreType, String passPhrase, String keyFileName) throws IOException {
		if( passPhrase == null ) {
			//  Without a password SecureBaseObject makes a context with no keys; this always needed one
			throw new IOException("A key store password is required");
		}
		SecureBaseObject sbo = new SecureBaseObject();
		sbo.setProtocol(sslOrTls);
		sbo.setAlgorithm(instanceName);
		sbo.setKeyStoreType(keyStoreType);
		sbo.setKeyStoreFileName(keyFileName);
		sbo.setKeyStorePassword(passPhrase);
		//  The JVM's default trust managers, as before (not SecureBaseObject's shared default)
		sbo.setTrustManagers(null);
		return sbo.getSSLContext();
	}

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
	 * Unlike start(), a server that can't start (port in use, bad key store, 
	 * no factories...) is reported to the caller instead of only being logged.
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
	 * @throws IllegalStateException if a secure factory is requested and the SSL context can't be created
	 * (previously null was returned and the cause only logged).
	 */
	public ServerSocketFactory getServerSocketFactory(boolean isSecure) 
	{
		ServerSocketFactory ret = null;

		if (isSecure) {
			try {
				// set up key manager to do server authentication
				ret = getSSLContext().getServerSocketFactory();
			} catch (Exception e) {
				throw new IllegalStateException("Can't create SSL server socket factory for server "+getName()+": "+e.getMessage(), e);
			}
		} else {
			ret =  ServerSocketFactory.getDefault();			
		}

		return ret;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}


	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	public ServerSocketFactory getServerSocketFactory() throws IOException {
		if( serverSocketFactory == null ) {
			synchronized(this) {
				if( serverSocketFactory == null ) {
					if( isSecure()) {
						serverSocketFactory = getSSLContext().getServerSocketFactory();
					} else {
						serverSocketFactory = ServerSocketFactory.getDefault();						
					}
				}
			}

		}
		return serverSocketFactory;
	}

	public void setServerSocketFactory(ServerSocketFactory serverSocketFactory) {
		this.serverSocketFactory = serverSocketFactory;
	}


	public void run() {

		// 1>  Make sure we have everything we need to run
		if(getConnectionFactory() == null ) {
			logError("No connection factory defined.");
			startupError = new IllegalStateException("No connection factory defined.");
			return;
		}
		if(getProcessorFactory() == null ) {
			logError("No processor factory defined.");
			startupError = new IllegalStateException("No processor factory defined.");
			return;
		}

		// 2> Create the server socket
		if( svr == null ) {
			try {
				svr = createServerSocket();
				svr.setSoTimeout(getAcceptTimeout());
			} catch (Exception e) {
				// e.g. port in use, or (secure) a missing key store or wrong password
				logError("Can't create ServerSockt",e);
				startupError = e;
			}
		}

		if( svr != null ) {
			startScheduler();
			started = running = true;
			lastAdmin = System.currentTimeMillis();

			logInfo("Server "+getName()+" is running on port "+getLocalPort()+".");
			while( !stopping ) {

				// 3>  Listen for connections.
				Socket socket = null;
				try {
					socket = svr.accept();
				} catch (SocketTimeoutException e) {
					// Ignore these
				} catch (IOException e) {
					if( stopping ) {
						break;
					}
					logError("Error in server run",e);
					if( svr.isClosed() ) {
						// Nothing more can be accepted, don't spin on the same error.
						break;
					}
				}

				if( socket != null ) {
					handleNewConnection(socket);
				}
				// doAdmin() runs on the server's scheduler (BJL-59), not here between accepts
			}

			//  We're done so close the serverSocket and exit.
			close(svr);
			stopScheduler();
			stopAllTasks();
			// Forget the closed socket so a later start() creates a new one
			// (a closed ServerSocket can't be reused, so a restart used to fail).
			svr = null;
		}

		running = false;
		logInfo("Server "+getName()+" has stopped.");
	}

	/**
	 * Stop the server. Also closes the listening socket so a blocked accept()
	 * returns at once; the accept loop sees {@code stopping} and exits.
	 * Previously stop() only set the flag, so it waited for accept() to time
	 * out (up to the accept timeout, 5 s by default).
	 */
	@Override
	public void stop() {
		super.stop();
		ServerSocket s = svr;
		if( s != null ) {
			IoUtils.closeQuietly(s);
		}
	}

	/**
	 * Create and bind the server socket. SO_REUSEADDR is set before binding 
	 * so a restart doesn't fail while the old port is in TIME_WAIT.
	 */
	protected ServerSocket createServerSocket() throws IOException {
		ServerSocketFactory factory = getServerSocketFactory();
		ServerSocket ret;
		try {
			ret = factory.createServerSocket();
		} catch (SocketException e) {
			// This factory can't create unbound sockets
			return factory.createServerSocket(getPort(), getBacklog());
		}
		try {
			ret.setReuseAddress(isReuseAddress());
			ret.bind(new InetSocketAddress(getPort()), getBacklog());
		} catch (IOException e) {
			IoUtils.closeQuietly(ret);
			throw e;
		}
		return ret;
	}

	/**
	 * @return the port the server is listening on (useful when the port was 0), or -1 if not bound.
	 */
	public int getLocalPort() {
		ServerSocket tmp = svr;
		return tmp == null ? -1 : tmp.getLocalPort();
	}

	/**
	 * @return the session thread setting: set with {@link #setVirtualThreads(VirtualThreads)},
	 * else the {@value #PROPERTY_VIRTUAL_THREADS} property, else {@link #DEFAULT_VIRTUAL_THREADS}
	 */
	public VirtualThreads getVirtualThreads() {
		VirtualThreads ret = virtualThreads;
		if( ret == null ) {
			ret = DEFAULT_VIRTUAL_THREADS;
			String tmp = getProperty(PROPERTY_VIRTUAL_THREADS);
			if( tmp != null && !tmp.trim().isEmpty() ) {
				try {
					ret = VirtualThreads.valueOf(tmp.trim().toUpperCase(java.util.Locale.ROOT));
				} catch (IllegalArgumentException e) {
					logError("Invalid "+PROPERTY_VIRTUAL_THREADS+" '"+tmp+"', expected OFF, ON or AUTO; using "+ret);
				}
			}
			virtualThreads = ret;
		}
		return ret;
	}

	/**
	 * Which threads run new sessions; sessions already running keep theirs.
	 * 
	 * @param virtualThreads OFF, ON or AUTO; null to read the property again
	 * @see VirtualThreads
	 */
	public void setVirtualThreads(VirtualThreads virtualThreads) {
		this.virtualThreads = virtualThreads;
	}

	/**
	 * @return true if new sessions will run on virtual threads (the setting, and what this JVM can do)
	 */
	public boolean isUsingVirtualThreads() {
		switch (getVirtualThreads()) {
		case ON:
			return BaseThread.isVirtualSupported();
		case AUTO: return BaseThread.isVirtualRecommended();
		default: return false;
		}
	}

	//  Overrides bjl_core's AbstractCoreServer (which defaults to false): this server defaults to true
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

	public boolean isReuseAddress() {
		return reuseAddress;
	}

	/**
	 * Set SO_REUSEADDR on the server socket (default true).
	 */
	public void setReuseAddress(boolean reuseAddress) {
		this.reuseAddress = reuseAddress;
	}

	public int getBacklog() {
		return backlog;
	}

	/**
	 * Pending connection queue length, 0 = JVM default.
	 */
	public void setBacklog(int backlog) {
		this.backlog = backlog;
	}

	/**
	 * Hand a newly accepted socket to a processor.
	 * 
	 * This runs on the accept thread so it must not block on the network 
	 * (no greeting writes, no TLS handshakes) and must never throw. 
	 * If the hand off fails for any reason the socket is closed.
	 * 
	 * @param socket
	 */
	protected void handleNewConnection(Socket socket) {
		logDebug("Incomming conenction from "+socket);
		boolean handedOff = false;
		try {
			int max = getMaxClients();
			if( max > 0 && activeClients.size() >= max ) {
				logInfo("Rejecting connection from "+socket+", "+activeClients.size()+" clients active (max="+max+")");
				rejectBusy(socket);
				return;
			}

			if( tcpNoDelay ) {
				// Request / response protocols: don't let Nagle delay small replies
				try {
					socket.setTcpNoDelay(true);
				} catch (SocketException e) {
					logDebug("Can't set TCP_NODELAY", e);
				}
			}

			if( isKeepAlive() ) {
				// KeepAlive property (bjl_core, default false): notice clients that vanish without closing
				try {
					socket.setKeepAlive(true);
				} catch (SocketException e) {
					logDebug("Can't set SO_KEEPALIVE", e);
				}
			}

			IConnection conn = getConnectionFactory().getConnection(socket);
			if( socket instanceof SSLSocket && !conn.isSecure()) {
				// TLS from the first byte: commands can see it, and STARTTLS is refused instead of nesting TLS
				conn.setSecure(true);
			}
			// Apply the server read timeout unless the factory already set one.
			if( conn.getTimeout() <= 0 && getConnectionTimeout() > 0 ) {
				conn.setTimeout(getConnectionTimeout());
			}

			IProcessor proc = getProcessor();
			proc.setConnection(conn);

			if( serverGreating != null && !serverGreating.isEmpty()) {
				if( proc instanceof AbstractProcessor ) {
					// Sent from the processor's own thread so a slow client or a TLS handshake can't stall accept().
					((AbstractProcessor) proc).setPendingGreeting(serverGreating);
				} else {
					// Legacy behavior for IProcessor implementations that don't extend AbstractProcessor.
					conn.writeLine(serverGreating);
				}
			}

			if( proc instanceof BaseThread ) {
				// Explicit either way, so bjl_core's own default doesn't apply to sessions (BJL-51)
				((BaseThread) proc).setVirtual(isUsingVirtualThreads());
			}
			activeClients.put(socket,proc);
			sessionTasks.put(proc, java.util.Collections.newSetFromMap(new ConcurrentHashMap<BaseThread, Boolean>()));
			proc.start();
			handedOff = true;
		} catch (Exception e) {
			logError("Can't start processor for "+socket, e);
		} finally {
			if( !handedOff ) {
				activeClients.remove(socket);
				IoUtils.closeQuietly(socket);
			}
		}
	}

	/**
	 * Called when max clients has been reached. The socket is closed by the caller.
	 * A busy message is only written to plain sockets, writing to an SSL socket 
	 * would start a handshake on the accept thread. 
	 */
	protected void rejectBusy(Socket socket) {
		String msg = getServerBusyMessage();
		if( msg != null && !msg.isEmpty() && !(socket instanceof SSLSocket)) {
			try {
				socket.getOutputStream().write((msg+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
			} catch (Exception e) {
				// Ignore, we're closing it anyway
			}
		}
	}



	/**
	 * Close all in process clients
	 * 
	 * @param svr
	 */
	private void close(ServerSocket svr) {
		for (IProcessor element : activeClients.values()) {
			IoUtils.closeQuietly(element.getConnection());
		}
		activeClients.clear();

		IoUtils.closeQuietly(svr);

	}

	// ------------------------------------------------------------------ session tasks (BJL-59)

	/**
	 * Start a thread that belongs to a session, so the server manages it: it runs on the same
	 * kind of thread as sessions ({@link #isUsingVirtualThreads()}), gets a name if it has none,
	 * and is stopped (stop() then interrupt) when its session ends or the server stops; while it
	 * runs, the idle check doesn't close its session. A task that has to release something to
	 * end (e.g. close a socket) should do that in its stop() method.
	 * 
	 * @param owner the session (a processor this server accepted and has not removed)
	 * @param task the thread to start
	 * @throws IllegalStateException if the session has ended or the server is stopping
	 */
	@Override
	public void startTask(IProcessor owner, BaseThread task) {
		java.util.Objects.requireNonNull(owner, "owner");
		java.util.Objects.requireNonNull(task, "task");
		if( stopping ) {
			throw new IllegalStateException("Server "+getName()+" is stopping");
		}
		// Started inside computeIfPresent so it can't race removeClient() for the same session
		java.util.Set<BaseThread> tasks = sessionTasks.computeIfPresent(owner, (k, set) -> {
			set.removeIf(t -> !t.isAlive());
			task.setVirtual(isUsingVirtualThreads());
			if( task.getName() == null ) {
				String session = owner instanceof BaseThread ? ((BaseThread) owner).getName() : null;
				task.setName((session == null || session.isEmpty() ? getName() : session)+"-task-"+taskNumber.incrementAndGet());
			}
			task.start();
			set.add(task);
			return set;
		});
		if( tasks == null ) {
			throw new IllegalStateException("The session has ended (or was not accepted by server "+getName()+")");
		}
		if( stopping ) {
			// The server began stopping while the task was starting
			stopTask(task);
		}
	}

	/**
	 * @param owner a session
	 * @return the session's tasks that are still running (a copy)
	 */
	public java.util.List<BaseThread> getTasks(IProcessor owner) {
		java.util.List<BaseThread> ret = new java.util.ArrayList<BaseThread>();
		java.util.Set<BaseThread> tasks = owner == null ? null : sessionTasks.get(owner);
		if( tasks != null ) {
			for (BaseThread t : tasks) {
				if( t.isAlive() ) {
					ret.add(t);
				}
			}
		}
		return ret;
	}

	/**
	 * @return the number of session tasks running on this server
	 */
	public int getTaskCount() {
		int ret = 0;
		for (java.util.Set<BaseThread> tasks : sessionTasks.values()) {
			for (BaseThread t : tasks) {
				if( t.isAlive() ) {
					ret++;
				}
			}
		}
		return ret;
	}

	private boolean hasRunningTask(IProcessor owner) {
		java.util.Set<BaseThread> tasks = sessionTasks.get(owner);
		if( tasks != null ) {
			for (BaseThread t : tasks) {
				if( t.isAlive() ) {
					return true;
				}
			}
		}
		return false;
	}

	private void stopTask(BaseThread task) {
		try {
			// stop() first (the task's own way to end), then interrupt; don't wait here
			task.stop(0, true);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			logDebug("Error stopping task "+task.getName(), e);
		}
	}

	private void stopTasks(java.util.Set<BaseThread> tasks) {
		if( tasks != null ) {
			for (BaseThread t : tasks) {
				if( t.isAlive() ) {
					stopTask(t);
				}
			}
		}
	}

	/** Stop every session's tasks and wait up to {@link #getTaskStopWait()} for them to end. */
	private void stopAllTasks() {
		java.util.List<BaseThread> all = new java.util.ArrayList<BaseThread>();
		for (java.util.Set<BaseThread> tasks : sessionTasks.values()) {
			all.addAll(tasks);
		}
		sessionTasks.clear();
		for (BaseThread t : all) {
			if( t.isAlive() ) {
				stopTask(t);
			}
		}
		long end = System.currentTimeMillis() + taskStopWait;
		for (BaseThread t : all) {
			long left = end - System.currentTimeMillis();
			if( left <= 0 ) {
				break;
			}
			try {
				t.join(left);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		for (BaseThread t : all) {
			if( t.isAlive() ) {
				logError("Task "+t.getName()+" did not stop within "+taskStopWait+" ms of server "+getName()+" stopping");
			}
		}
	}

	/**
	 * @return how long the stopping server waits for session tasks to end (ms)
	 */
	public long getTaskStopWait() {
		return taskStopWait;
	}

	/**
	 * @param milliSeconds how long the stopping server waits for session tasks to end (0 = don't wait)
	 */
	public void setTaskStopWait(long milliSeconds) {
		this.taskStopWait = Math.max(0, milliSeconds);
	}

	// ------------------------------------------------------------------ scheduler (BJL-59)

	private void startScheduler() {
		ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory(getName()+"-scheduler"));
		exec.setRemoveOnCancelPolicy(true);
		exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		scheduler = exec;
		scheduleAdmin(exec);
	}

	private void scheduleAdmin(ScheduledThreadPoolExecutor exec) {
		long freq = Math.max(1, adminFreq);
		// Idle connections are reaped on a quiet server too
		adminTask = exec.scheduleWithFixedDelay(logged("doAdmin", this::doAdmin), freq, freq, TimeUnit.MILLISECONDS);
	}

	private void stopScheduler() {
		ScheduledThreadPoolExecutor exec = scheduler;
		scheduler = null;
		adminTask = null;
		if( exec != null ) {
			exec.shutdownNow();
			try {
				if( !exec.awaitTermination(taskStopWait, TimeUnit.MILLISECONDS) ) {
					logError("Scheduled work of server "+getName()+" did not stop within "+taskStopWait+" ms");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private ScheduledThreadPoolExecutor runningScheduler() {
		ScheduledThreadPoolExecutor exec = scheduler;
		if( exec == null || exec.isShutdown() ) {
			throw new IllegalStateException("Server "+getName()+" is not running");
		}
		return exec;
	}

	/** An exception in one run is logged, and a periodic task keeps running. */
	private Runnable logged(String what, Runnable task) {
		return () -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				logError("Error in scheduled task "+what+" of server "+getName(), e);
			}
		};
	}

	/**
	 * Run a task once after a delay on the server's scheduler (one daemon platform thread per
	 * running server, shut down when the server stops). Keep scheduled work short; start a
	 * session task for anything that blocks.
	 * 
	 * @throws IllegalStateException if the server is not running
	 */
	@Override
	public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
		return runningScheduler().schedule(logged(String.valueOf(task), java.util.Objects.requireNonNull(task, "task")), delay, unit);
	}

	/**
	 * Run a task periodically on the server's scheduler until it is cancelled or the server
	 * stops. An exception in one run is logged and doesn't cancel later runs.
	 * 
	 * @throws IllegalStateException if the server is not running
	 * @see #schedule(Runnable, long, TimeUnit)
	 */
	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
		return runningScheduler().scheduleAtFixedRate(logged(String.valueOf(task), java.util.Objects.requireNonNull(task, "task")), initialDelay, period, unit);
	}

	/**
	 * @return how often (ms) the idle connection check runs (default 5 minutes)
	 */
	public long getAdminFrequency() {
		return adminFreq;
	}

	/**
	 * @param milliSeconds how often the idle connection check runs; a running server reschedules it
	 */
	public void setAdminFrequency(long milliSeconds) {
		this.adminFreq = Math.max(1, milliSeconds);
		ScheduledThreadPoolExecutor exec = scheduler;
		ScheduledFuture<?> admin = adminTask;
		if( exec != null && !exec.isShutdown() ) {
			if( admin != null ) {
				admin.cancel(false);
			}
			scheduleAdmin(exec);
		}
	}

	protected void doAdmin() {
		//  Check for idle connections
		lastAdmin = System.currentTimeMillis();
		for (Iterator<Map.Entry<Socket, IProcessor>> it = activeClients.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Socket, IProcessor> entry = it.next();
			try {
				IConnection con = entry.getValue().getConnection();
				Socket sock = con == null ? null : con.getSocket();
				if( sock == null || sock.isClosed() ) {
					// Already closed, the processor is exiting or failed to remove itself.
					it.remove();
				} else if((lastAdmin-con.getLastReadTime()) > maxIdleConnection  && (lastAdmin-con.getLastWriteTime()) > maxIdleConnection
						&& !hasRunningTask(entry.getValue())) {
					logDebug("Closing idle connection "+sock);
					it.remove();
					IoUtils.closeQuietly(con);
				}
			} catch (Throwable e) {
				// One bad entry must not stop the idle check for everyone else.
				logDebug("Error in doAdmin for "+entry.getKey(), e);
				it.remove();
				IoUtils.closeQuietly(entry.getValue().getConnection());
			}
		}
	}

	public IProcessor getProcessor() throws InstantiationException, IllegalAccessException {
		IProcessor ret = getProcessorFactory().getProcessor();
		ret.setServer(this);
		return ret;
	}


	public int getAcceptTimeout() {
		return acceptTimeout;
	}


	public void setAcceptTimeout(int milliSeconds) {
		acceptTimeout = milliSeconds;

	}

	/**
	 * Read timeout (SO_TIMEOUT) applied to each accepted connection 
	 * unless the connection factory already set one. 0 = wait forever.
	 */
	public int getConnectionTimeout() {
		return connectionTimeout;
	}

	public void setConnectionTimeout(int milliSeconds) {
		this.connectionTimeout = milliSeconds;
	}

	/**
	 * Maximum number of concurrent clients, 0 = unlimited.
	 */
	public int getMaxClients() {
		return maxClients;
	}

	public void setMaxClients(int maxClients) {
		this.maxClients = maxClients;
	}

	/**
	 * Optional line sent to clients rejected because max clients has been reached 
	 * (plain sockets only), e.g. "421 Too many connections".
	 */
	public String getServerBusyMessage() {
		return serverBusyMessage;
	}

	public void setServerBusyMessage(String serverBusyMessage) {
		this.serverBusyMessage = serverBusyMessage;
	}

	public long getMaxIdleConnection() {
		return maxIdleConnection;
	}

	public void setMaxIdleConnection(long milliSeconds) {
		this.maxIdleConnection = milliSeconds;
	}


	public IProcessorFactory getProcessorFactory() {
		return processorFactory;
	}

	public void setProcessorFactory(IProcessorFactory processorFactory) {
		this.processorFactory = processorFactory;
	}

	public IConnectionFactory getConnectionFactory() {

		return connectionFactory;
	}

	public void setConnectionFactory(IConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	public Map<String, Object> getRuntimeValues() {
		return runtimeValues;
	}


	/**
	 * The values are copied into a thread safe map.
	 */
	public void setRuntimeValues(Map<String, Object> runtimeValues) {
		Map<String, Object> tmp = new ConcurrentHashMap<String, Object>();
		if( runtimeValues != null ) {
			for (Map.Entry<String, Object> e : runtimeValues.entrySet()) {
				if( e.getKey() != null && e.getValue() != null ) {
					tmp.put(e.getKey(), e.getValue());
				}
			}
		}
		this.runtimeValues = tmp;
	}

	public Object getRuntimeValue(String name) {
		return name == null ? null : getRuntimeValues().get(name);
	}

	/**
	 * Setting a null value removes the name.
	 */
	public void setRuntimeValue(String name, Object value) {
		if( name == null ) {
			return;
		}
		if( value == null ) {
			getRuntimeValues().remove(name);
		} else {
			getRuntimeValues().put(name, value);
		}
	}

	public Object removeRuntimeValue(String name) {
		return name == null ? null : getRuntimeValues().remove(name);
	}

	public void removeClient(IProcessor processor) {
		if( processor == null ) {
			return;
		}
		// The session is over: so are the threads it started (BJL-59)
		stopTasks(sessionTasks.remove(processor));
		// Fast path, the connection's current socket is usually the key.
		IConnection con = processor.getConnection();
		if( con != null ) {
			Socket sock = con.getSocket();
			if( sock != null && activeClients.remove(sock, processor)) {
				return;
			}
		}
		// The socket may have been replaced (e.g. after negotiateSecureSocket) or 
		// already cleared by close(), so fall back to removing by value.
		activeClients.values().removeIf(p -> p == processor);
	}

	public Map<Socket, IProcessor> getActiveClients() {
		return activeClients;
	}


	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.IServer#isDebug()
	 */
	public boolean isDebug() {
		return debug;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.IServer#setDebug(boolean)
	 */
	public void setDebug(boolean debug) {
		this.debug = debug;

	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.IServer#authenticate(java.lang.String, byte[])
	 */
	public IPrincipal authenticate(String user, byte[] credentials) {
		IPrincipal ret = null;
		IAccessControlList acl = getAccessControl();
		if(acl != null ) {
			IPrincipal tmp = acl.getPrincipal(user);
			if( tmp == null ) {
				// Same work as a real check so timing doesn't reveal valid user names
				acl.authenticateUnknownUser(credentials);
			} else if( tmp.authenticate(credentials)) {
				ret = new ImmutablePrincipal(tmp);
				ret.setState(State.Authenticated);
			}
		}
		
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.IServer#isAuthorized(java.security.Principal, IPermission)
	 */
	public boolean isAuthorized(IPrincipal user, IPermission action) {
		boolean ret = false;
		if( user == null ) {
			// Not logged in
			return false;
		}
		IAccessControlList acl = getAccessControl();
		if( acl != null ) {
			ret = acl.checkPermission(user, action);
		}
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.IServer#getAcl()
	 */
	@Override
	public IAccessControlList getAccessControl() {
		if( accessControl == null && !noAccessControl ) {
			synchronized (this) {
				if( accessControl == null && !noAccessControl ) {
					String tmp = getProperty(AUTHENTICATION_PROVIDER_PROPERTY);
					if(tmp != null ) {
						try {
							Class<?> authClass = Class.forName(tmp);
							IAccessControlList auth = (IAccessControlList)authClass.getDeclaredConstructor().newInstance();
							auth.initialize(this);
							setAccessControl(auth);
						} catch (Exception e) {
							logError("Fatal Error! Can't configure access control class='"+tmp+"'",e);
							// Just in case logging is turned off
							System.err.println("Fatal Error! Can't configure access control class='"+tmp+"'"+e);
							throw new IllegalStateException("Fatal Error! Can't configure access control class='"+tmp,e);
						}
					} else {
						// remembered and logged once (BJL-42)
						noAccessControl = true;
						logInfo("No access control defined in server "+getName());
					}
				}
			}
		}

		return accessControl;
	}

	@Override
	public void setAccessControl(IAccessControlList acl) {
		this.accessControl = acl;
		// null: look at the provider property again on the next use
		this.noAccessControl = false;
	}


}
