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
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import us.bringardner.core.SecureBaseObject;
import us.bringardner.net.framework.Connection;




public class Client extends Connection implements IClient {

	public static final int DEFAULT_CONNECT_TIMEOUT = 30000;

	private volatile SecureBaseObject context;
	private int port;
	private String host;
	private volatile boolean connected;
	private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
	private volatile boolean trustAllCertificates = false;
	private volatile boolean verifyHostname = true;
	
	public Client(boolean useCRLF) {
		super(useCRLF);
	}
	public Client() {
		this(true);
	}
	
	public Client(String host, int port) {
		this(host,port,true);
	}
	
	
	public Client(String host, int port, boolean useCRLF) {
		super(useCRLF);
		setHost(host);
		setPort(port);
	}
	
	
	public SecureBaseObject getContext() {
		return context;
	}
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
			setSocket(sock);
			connected = true;
		} catch (IOException e) {
			logError("Can't Connect to "+getHost()+":"+getPort(),e);
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
		// Rebuild the context with the new trust managers on the next negotiation
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

	@Override
	protected boolean isClientMode() {
		return true;
	}

	@Override
	protected String getPeerHost() {
		return getHost();
	}

	@Override
	public SSLContext getSSLContext(String sslOrTsl) throws IOException {
		
		SecureBaseObject ctx = context;
		if( ctx == null ) {
			synchronized (this) {
				ctx = context;
				if( ctx == null ) {
					ctx = new SecureBaseObject();
					if( trustAllCertificates ) {
						ctx.setTrustManagers(new TrustManager[] {new TrustAllManager()});
					} else {
						// JVM trust store, then certificates the user has accepted (see DynamicTrustManager.setDefaultValidator).
						ctx.setTrustManagers(new TrustManager[] {new DynamicTrustManager()});
					}
					context = ctx;
				}
			}			
		}
		
		ctx.setProtocol(sslOrTsl);
		return ctx.getSSLContext();
	}

	/**
	 * Accepts every certificate. Only used when setTrustAllCertificates(true).
	 */
	private static class TrustAllManager implements X509TrustManager {
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		}
	}

}
