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
package us.bringardner.net.framework.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import us.bringardner.core.util.TrustAllCertificates;
import us.bringardner.core.SecureBaseObject;
import us.bringardner.net.framework.Connection;




public class Client extends Connection implements IClient {

	public static final int DEFAULT_CONNECT_TIMEOUT = 30000;
	/** Default read timeout for new clients: 5 minutes, generous so slow commands still complete. */
	public static final int DEFAULT_READ_TIMEOUT = 5*60*1000;
	private static volatile int defaultReadTimeout = DEFAULT_READ_TIMEOUT;

	/**
	 * Properties (milliseconds, 0 = no timeout) read when a client is created, see BaseObject.getProperty:
	 * e.g. -Dus.bringardner.net.framework.client.CommandClient.readTimeout=600000 for one client class,
	 * -DreadTimeout=600000 for all, or the same names in a class-named .properties file on the class path.
	 * Calling setTimeout() / setConnectTimeout() afterwards overrides them.
	 */
	public static final String PROPERTY_READ_TIMEOUT = "readTimeout";
	public static final String PROPERTY_CONNECT_TIMEOUT = "connectTimeout";

	private volatile SecureBaseObject context;
	/**
	 * The default TLS contexts, one per protocol and trust mode, shared by every Client in the
	 * JVM. Sessions are cached per context, so a client that reconnects to the same server can
	 * resume its TLS session (an abbreviated handshake) instead of a full one (BJL-39).
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String, SecureBaseObject> SHARED_CONTEXTS = new java.util.concurrent.ConcurrentHashMap<>();
	private int port;
	private String host;
	private volatile boolean connected;
	private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
	private volatile boolean trustAllCertificates = false;
	private volatile boolean verifyHostname = true;
	private boolean tcpNoDelay = true;
	private volatile IOException lastConnectError;
	// The factory in use before negotiateSecureSocket switched to TLS. connect() goes back to it,
	// the server expects a plain connection first.
	private volatile SocketFactory connectSocketFactory;
	private boolean negotiating;
	
	public Client(boolean useCRLF) {
		super(useCRLF);
		configureTimeouts();
	}
	public Client() {
		this(true);
	}
	
	public Client(String host, int port) {
		this(host,port,true);
	}
	
	
	public Client(String host, int port, boolean useCRLF) {
		super(useCRLF);
		configureTimeouts();
		setHost(host);
		setPort(port);
	}
	
	
	/**
	 * @return the TLS configuration set with {@link #setContext(SecureBaseObject)}, or null if
	 * this client uses the shared default one
	 */
	public SecureBaseObject getContext() {
		return context;
	}

	/**
	 * Use this TLS configuration instead of the shared default. Reuse the same object for
	 * several clients (or reconnects) to let them resume TLS sessions.
	 * @param context the configuration, or null for the shared default
	 */
	public void setContext(SecureBaseObject context) {
		this.context = context;
	}
	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#getHost()
	 */
	public String getHost() {
		return host;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#setHost(java.lang.String)
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#getPort()
	 */
	public int getPort() {
		return port;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#setPort(int)
	 */
	public void setPort(int port) {
		this.port = port;
	}


	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#isConnected()
	 */
	public boolean isConnected() {
		return connected;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#connect()
	 */
	public boolean connect() throws IOException {
		if( connected ) {
			// Don't leak the previous socket
			close();
		}
		// A new connection starts plain even if the last one switched to TLS
		restoreConnectSocketFactory();

		lastConnectError = null;
		Socket sock = null;
		try {
			try {
				sock = getSocketFactory().createSocket();
			} catch (SocketException e) {
				// This factory doesn't support unconnected sockets
				sock = null;
			}
			if( sock != null ) {
				sock.connect(new InetSocketAddress(getHost(),getPort()), getConnectTimeout());
			} else {
				// Fall back to the OS connect timeout.
				sock = getSocketFactory().createSocket(getHost(),getPort());
			}

			boolean implicitTls = sock instanceof SSLSocket;
			if( implicitTls ) {
				// Set up host name verification before the handshake (it starts on first I/O)
				SSLSocket ssl = (SSLSocket) sock;
				ssl.setUseClientMode(true);
				configureClientSsl(ssl);
			}
			setSecure(implicitTls);
			if( tcpNoDelay ) {
				try {
					sock.setTcpNoDelay(true);
				} catch (SocketException e) {
					logDebug("Can't set TCP_NODELAY", e);
				}
			}
			setSocket(sock);
			connected = true;
		} catch (IOException e) {
			logError("Can't Connect to "+getHost()+":"+getPort(),e);
			lastConnectError = e;
			closeQuietly(sock);
		}

		return connected;
	}

	private static void closeQuietly(Socket sock) {
		if( sock != null ) {
			try {
				sock.close();
			} catch (Exception e) {
			}
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.client.IClient#close()
	 */
	public void close() throws IOException {
		connected = false;
		try {
			super.close();
		} finally {
			// A new connect() starts from a plain socket
			setSecure(false);
		}
	}

	/*
	 * Without a read timeout a dead server would block readLine() forever. setTimeout(0) restores that.
	 */
	private void configureTimeouts() {
		setTimeout(getConfiguredTimeout(PROPERTY_READ_TIMEOUT, getDefaultReadTimeout()));
		setConnectTimeout(getConfiguredTimeout(PROPERTY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT));
	}

	private int getConfiguredTimeout(String name, int defaultValue) {
		String value = getProperty(name, null);
		if( value != null ) {
			try {
				return Math.max(0, Integer.parseInt(value.trim()));
			} catch (NumberFormatException e) {
				logError("Invalid "+name+" '"+value+"', using "+defaultValue);
			}
		}
		return defaultValue;
	}

	/**
	 * @return why the last connect() returned false (e.g. ConnectException, UnknownHostException,
	 * SocketTimeoutException, SSLHandshakeException), or null if it succeeded.
	 */
	public IOException getLastConnectError() {
		return lastConnectError;
	}

	public static int getDefaultReadTimeout() {
		return defaultReadTimeout;
	}

	/**
	 * Read timeout (milliseconds) for clients created after this call, 0 = wait forever.
	 * The readTimeout property, if set, takes precedence. Use setTimeout() to change a single client.
	 */
	public static void setDefaultReadTimeout(int milliSeconds) {
		defaultReadTimeout = milliSeconds;
	}

	public boolean isTcpNoDelay() {
		return tcpNoDelay;
	}

	/**
	 * Set TCP_NODELAY on new connections (default true), avoids Nagle delays on small commands.
	 */
	public void setTcpNoDelay(boolean tcpNoDelay) {
		this.tcpNoDelay = tcpNoDelay;
	}

	/**
	 * Connect timeout in milliseconds (0 = OS default).
	 */
	public int getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(int milliSeconds) {
		this.connectTimeout = milliSeconds;
	}

	/**
	 * When true (NOT recommended) any server certificate is accepted and host names are not checked.
	 * This was the behavior of earlier versions. The default (false) validates certificates with the
	 * JVM trust store plus certificates accepted through {@link DynamicTrustManager}.
	 */
	public boolean isTrustAllCertificates() {
		return trustAllCertificates;
	}

	public synchronized void setTrustAllCertificates(boolean trustAll) {
		this.trustAllCertificates = trustAll;
		// Use the default context for the new trust mode on the next negotiation (as before,
		// this also drops a context set with setContext)
		context = null;
	}

	/**
	 * When true (the default) the server certificate must match the host name used to connect.
	 * Ignored when trust all certificates is enabled.
	 */
	@Override
	public boolean isVerifyHostname() {
		return verifyHostname && !trustAllCertificates;
	}

	public void setVerifyHostname(boolean verifyHostname) {
		this.verifyHostname = verifyHostname;
	}

	/**
	 * Setting a factory explicitly replaces the one connect() would otherwise go back to.
	 */
	@Override
	public void setSocketFactory(SocketFactory socketFactory) {
		super.setSocketFactory(socketFactory);
		if( !negotiating ) {
			connectSocketFactory = null;
		}
	}

	/**
	 * After a successful switch to TLS getSocketFactory() returns the TLS factory 
	 * (for secondary connections), but connect() still uses the factory from before the switch.
	 * Passing null ends TLS and restores that factory.
	 */
	@Override
	public synchronized void negotiateSecureSocket(String sslOrTsl) throws IOException {
		SocketFactory before = getSocketFactory();
		negotiating = true;
		try {
			super.negotiateSecureSocket(sslOrTsl);
		} finally {
			negotiating = false;
		}
		if( sslOrTsl == null ) {
			restoreConnectSocketFactory();
		} else if( connectSocketFactory == null && before != getSocketFactory()) {
			connectSocketFactory = before;
		}
	}

	private void restoreConnectSocketFactory() {
		SocketFactory tmp = connectSocketFactory;
		if( tmp != null ) {
			connectSocketFactory = null;
			super.setSocketFactory(tmp);
		}
	}

	@Override
	protected boolean isClientMode() {
		return true;
	}

	@Override
	protected String getPeerHost() {
		return getHost();
	}

	/**
	 * The TLS context for this client: the one set with {@link #setContext(SecureBaseObject)},
	 * or the shared default for the protocol and trust mode.
	 * <p>
	 * The context is reused, not rebuilt: this used to call setProtocol() on every
	 * negotiation, which throws the SSLContext (and its session cache) away, and every Client
	 * had its own, so no connection could ever resume a TLS session (BJL-39).
	 */
	@Override
	public SSLContext getSSLContext(String sslOrTsl) throws IOException {
		String protocol = sslOrTsl == null ? "TLS" : sslOrTsl;
		SecureBaseObject ctx = context;
		if( ctx != null ) {
			synchronized (ctx) {
				// only when it really changes: setProtocol discards the context
				if( !protocol.equals(ctx.getProtocol()) ) {
					ctx.setProtocol(protocol);
				}
			}
			return ctx.getSSLContext();
		}
		final boolean trustAll = trustAllCertificates;
		ctx = SHARED_CONTEXTS.computeIfAbsent(protocol+"|"+trustAll, key -> {
			SecureBaseObject shared = new SecureBaseObject();
			if( trustAll ) {
				shared.setTrustManagers(TrustAllCertificates.trustManagers());
			} else {
				// JVM trust store, then certificates the user has accepted (see DynamicTrustManager.setDefaultValidator).
				shared.setTrustManagers(new TrustManager[] {new DynamicTrustManager()});
			}
			shared.setProtocol(protocol);
			return shared;
		});
		return ctx.getSSLContext();
	}


}
