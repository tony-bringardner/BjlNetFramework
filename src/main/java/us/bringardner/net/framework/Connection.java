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
package us.bringardner.net.framework;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import us.bringardner.core.BaseObject;
import us.bringardner.core.SecureBaseObject;
import us.bringardner.io.AbstractLineReader;
import us.bringardner.io.CRLFLineReader;
import us.bringardner.io.CRLFLineWriter;
import us.bringardner.io.ILineReader;
import us.bringardner.io.ILineWriter;
import us.bringardner.io.LFLineReader;
import us.bringardner.io.LFLineWriter;
import us.bringardner.net.framework.server.IServer;
import us.bringardner.net.framework.server.Server;

public abstract class Connection extends BaseObject implements IConnection {

	private volatile Socket socket;
	// volatile: close() may be called from another thread (e.g. Server.doAdmin) while the processor is reading
	private volatile ILineReader reader;
	private volatile ILineWriter writer;
	private IServer server;
	private boolean debug;
	private int timeout;
	private boolean secure;
	private volatile SocketFactory socketFactory;
	private int outBufSize = 1024*10;
	private volatile SSLSocket sslSocket;
	private boolean useCRLF=true;
	// Counts as activity so a new connection isn't treated as idle before its first read / write
	private volatile long connectTime;

	/** Longest line accepted by readLine() (bytes), 0 = unlimited. */
	public static final int DEFAULT_MAX_LINE_LENGTH = 64*1024;
	private static volatile int defaultMaxLineLength = DEFAULT_MAX_LINE_LENGTH;
	private volatile int maxLineLength = defaultMaxLineLength;
	
	
	public Connection(boolean useCRLF) {
		super();		
		getLogger().setLevel(Server.getDefaultLogLevel());
		this.useCRLF = useCRLF;
	}

	
	public Connection(Socket socket,boolean useCRLF) throws IOException {
		this(useCRLF);		
		setSocket(socket);
	}
	
	/**
	 * In many cases, if a secure socket is required, the
	 * SocketFactory takes care of the details.
	 * 
	 * In some cases you need to change an existing socket 
	 * to a secure socket.
	 * 
	 * @param sslOrTsl
	 * 
	 * @throws IOException
	 */
	public void negotiateSecureSocket(String sslOrTsl) throws IOException {
		if( sslOrTsl == null ) {
			// End TLS and go back to the plain socket.
			if( sslSocket != null ) {
				try {
					sslSocket.close();
				} finally {
					sslSocket = null;
					secure = false;
					configureStreams();
				}
			}
			return;
		}

		// Check before touching the current socket so a second call can't break a live TLS session.
		if( isSecure() || sslSocket != null ) {
			throw new IllegalStateException("Can not negotiate a secure channel from a secure channel.");
		}

		SSLContext ctx = null;
		try {
			ctx = getSSLContext(sslOrTsl);
		} catch (Throwable e) {
			if (e instanceof IOException) {
				throw (IOException) e;				
			} else {
				throw new IOException(e);
			}			
		}
		if( ctx == null ) {
			throw new IOException("No SSLContext available for "+sslOrTsl);
		}

		SSLSocketFactory factory = ctx.getSocketFactory();
		Socket plain = getSocket();
		boolean clientMode = isClientMode();
		SSLSocket tmp = (SSLSocket)factory.createSocket(plain, clientMode ? getPeerHost() : null, plain.getPort(), false);
		// Some clients don;t support v1.3
		String force = System.getProperty(SecureBaseObject.PROPERTY_FORCE_TLS_VERSION);
		if( force != null) {
			force = force.trim();
			if( !force.isEmpty()) {
				tmp.setEnabledProtocols(new String[] {force});		
			}
		}

		tmp.setUseClientMode(clientMode);
		if( clientMode ) {
			configureClientSsl(tmp);
		} else {
			tmp.setWantClientAuth(false);
		}
		tmp.startHandshake();
		sslSocket = tmp;
		secure = true;
		//  Any new connections (e.g. FTP style data connections) will be secure.
		//  Set after the handshake so a failed negotiation leaves the factory unchanged.
		setSocketFactory(factory);

		configureStreams();
	}

	/**
	 * @return true if this end of the connection is the client (TLS client mode). The server side returns false.
	 */
	protected boolean isClientMode() {
		return false;
	}

	/**
	 * @return the host name of the peer, used for TLS SNI and host name verification in client mode.
	 */
	protected String getPeerHost() {
		return null;
	}

	/**
	 * @return true if the peer's certificate must match getPeerHost() (client mode only).
	 */
	protected boolean isVerifyHostname() {
		return false;
	}

	/**
	 * Apply client side TLS settings before the handshake.
	 */
	protected void configureClientSsl(SSLSocket sock) {
		if( isVerifyHostname() && getPeerHost() != null ) {
			SSLParameters params = sock.getSSLParameters();
			params.setEndpointIdentificationAlgorithm("HTTPS");
			sock.setSSLParameters(params);
		}
	}
	
	public abstract SSLContext getSSLContext(String sslOrTsl) throws IOException ;


	private void configureStreams() throws IOException {
		Socket socket = getSocket();
		
		AbstractLineReader r;
		if (useCRLF) {
			r = new CRLFLineReader(socket.getInputStream());
			writer = new CRLFLineWriter(socket.getOutputStream(),outBufSize);
		} else {
			r = new LFLineReader(socket.getInputStream());
			writer = new LFLineWriter(socket.getOutputStream(),outBufSize);
		}
		r.setMaxLineLength(maxLineLength);
		reader = r;
	}

	public static int getDefaultMaxLineLength() {
		return defaultMaxLineLength;
	}

	/**
	 * Default for new connections, 0 = unlimited.
	 */
	public static void setDefaultMaxLineLength(int max) {
		defaultMaxLineLength = max;
	}

	public int getMaxLineLength() {
		return maxLineLength;
	}

	/**
	 * Longest line readLine() will accept (bytes, not counting the terminator), 0 = unlimited.
	 * A longer line throws an IOException (LineTooLongException) which ends a server session.
	 * Without a limit a client can exhaust memory by sending data with no line terminator.
	 */
	public void setMaxLineLength(int max) {
		maxLineLength = max;
		ILineReader r = reader;
		if( r instanceof AbstractLineReader ) {
			((AbstractLineReader) r).setMaxLineLength(max);
		}
	}

	public boolean isSecure() {
		return secure;
	}

	
	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	public SocketFactory getSocketFactory() {
		if( socketFactory == null ) {
			synchronized(this) {
				if( socketFactory == null ) {
					socketFactory = SocketFactory.getDefault();
				}
			}
		}

		return socketFactory;
	}

	public void setSocketFactory(SocketFactory socketFactory) {
		this.socketFactory = socketFactory;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
		if( socket != null ) {
			try {
				socket.setSoTimeout(timeout);
			} catch (SocketException e) {
				logError("Error setting SoTimeout",e);
			}
		}
	}

	public void close() throws IOException {
		logDebug("Clossing socket="+socket);
		SSLSocket tls = sslSocket;
		if( tls != null && !tls.isClosed() && !tls.isOutputShutdown() ) {
			/*
			 * End the TLS session with just a close_notify (RFC 8446 section 6.1). Java's
			 * close() of a TLS 1.3 socket sends a user_canceled alert first, which GnuTLS
			 * peers (FileZilla, lftp) report as a fatal alert (BJL-2).
			 */
			try {
				ILineWriter w = writer;
				if( w != null ) {
					w.flush();
				}
				tls.shutdownOutput();
			} catch (IOException | UnsupportedOperationException e) {
				logDebug("TLS shutdown before close failed", e);
			}
		}
		if( reader != null ) {
			try {
				reader.close();
			} catch (IOException e) {
				// Ignore error here
			}
			reader = null;
		}
		if( writer != null ) {
			try {
				writer.close();
			} catch (IOException e) {
				// Ingore error here
			}
			writer = null;
		}
		if( sslSocket != null ) {
			try {
				sslSocket.close();
			} catch (IOException e) {
				// Ingore error here
			}
			sslSocket = null;
		}
		if( socket != null ) {
			try {
				socket.close();
			} catch (IOException e) {
				// Ingore error here
			}
			socket = null;
		}
	}

	private ILineReader openReader() throws IOException {
		ILineReader ret = reader;
		if( ret == null ) {
			throw new IOException("Connection is closed");
		}
		return ret;
	}

	private ILineWriter openWriter() throws IOException {
		ILineWriter ret = writer;
		if( ret == null ) {
			throw new IOException("Connection is closed");
		}
		return ret;
	}


	public IServer getServer() {
		return server;
	}

	public Socket getSocket() {
		Socket ret = socket;
		if( sslSocket != null ) {
			ret = sslSocket;
		}
		return ret;
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean trueOrFalse) {
		this.debug = trueOrFalse;
	}

	public void setServer(IServer server) {
		this.server = server;
	}

	public void setSocket(Socket socket) throws IOException {
		this.socket = socket;
		connectTime = System.currentTimeMillis();
		try {
			logDebug("Connection from "+socket);
			configureStreams();
			int to = getTimeout();
			logDebug("Setting timeout = "+to);
			socket.setSoTimeout(to);
		} catch (SocketException e) {
			logError("Error setting SoTimeout", e);
		}
	}

	public long getBytesIn() {
		return reader.getBytesIn();
	}

	public final String readLine() throws IOException {
		String ret = openReader().readLine();
		
		return ret;
	}
	

	public final void flush() throws IOException {
		openWriter().flush();
	}

	

	public boolean isAutoFlush() {
		return writer.isAutoFlush();
	}

	public void setAutoFlush(boolean trueOrFalse) {
		writer.setAutoFlush(trueOrFalse);
	}

	/**
	 * Writes all the lines into the output buffer with auto flush off, then flushes once
	 * (with auto flush on, the default, every writeLine is its own write and TCP segment).
	 */
	@Override
	public final void writeLines(java.util.List<String> lines) throws IOException {
		ILineWriter w = openWriter();
		synchronized (w) {
			boolean autoFlush = w.isAutoFlush();
			w.setAutoFlush(false);
			try {
				for (String line : lines) {
					w.writeLine(line);
				}
			} finally {
				w.setAutoFlush(autoFlush);
			}
			w.flush();
		}
	}

	public final void writeLine(String line) throws IOException {
		openWriter().writeLine(line);
		
	}
	
	public final void write(String line) throws IOException {
		openWriter().write(line);		
	}
	

	public final ILineReader getReader() {
		return reader;
	}

	public final ILineWriter getWriter() {
		return writer;
	}

	public final int inputAvailable() throws IOException {
		return openReader().inputAvailable();
	}

	public long getLastReadTime() {
		ILineReader r = reader;
		return r == null ? 0 : Math.max(r.getLastReadTime(), connectTime);
	}

	public long getBytesOut() {
		return writer.getBytesOut();
	}

	public long getLastWriteTime() {
		ILineWriter w = writer;
		return w == null ? 0 : Math.max(w.getLastWriteTime(), connectTime);
	}
	

}

