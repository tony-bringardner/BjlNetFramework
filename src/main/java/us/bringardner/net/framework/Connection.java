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
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import us.bringardner.core.BaseObject;
import us.bringardner.core.SecureBaseObject;
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
	private ILineReader reader;
	private ILineWriter writer;
	private IServer server;
	private boolean debug;
	private int timeout;
	private boolean secure;
	private volatile SocketFactory socketFactory;
	private int outBufSize = 1024*10;
	private volatile SSLSocket sslSocket;
	private boolean useCRLF=true;
	
	
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
		if( sslSocket != null ) {
			sslSocket.close();
			sslSocket = null;
		}

		if( sslOrTsl == null ) {
			return;
		}
		
		if( isSecure()) {
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
		
		SSLSocketFactory factory = ctx.getSocketFactory();
		//  Any new connections will be secure
		setSocketFactory(factory);
		
		sslSocket = (SSLSocket)factory.createSocket(getSocket(),null, socket.getPort(), false);
		// Some clients don;t support v1.3
		String force = System.getProperty(SecureBaseObject.PROPERTY_FORCE_TLS_VERSION);
		if( force != null) {
			force = force.trim();
			if( !force.isEmpty()) {
				sslSocket.setEnabledProtocols(new String[] {force});		
			}
		}
		
		
		
		sslSocket.setWantClientAuth(false);
		sslSocket.setUseClientMode(false);
		sslSocket.startHandshake();
		secure = true;
		
		configureStreams();
		
		
	}
	
	public abstract SSLContext getSSLContext(String sslOrTsl) throws IOException ;


	private void configureStreams() throws IOException {
		Socket socket = getSocket();
		
		if (useCRLF) {
			reader = new CRLFLineReader(socket.getInputStream());
			writer = new CRLFLineWriter(socket.getOutputStream(),outBufSize);
		} else {
			reader = new LFLineReader(socket.getInputStream());
			writer = new LFLineWriter(socket.getOutputStream(),outBufSize);
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

	public void close() {
		logDebug("Clossing socket="+socket);
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
		if( socket != null ) {
			try {
				socket.close();
			} catch (IOException e) {
				// Ingore error here
			}
			socket = null;
		}
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
		String ret = reader.readLine();
		
		return ret;
	}
	

	public final void flush() throws IOException {
		writer.flush();
	}

	

	public boolean isAutoFlush() {
		return writer.isAutoFlush();
	}

	public void setAutoFlush(boolean trueOrFalse) {
		writer.setAutoFlush(trueOrFalse);
	}

	public final void writeLine(String line) throws IOException {
		writer.writeLine(line);
		
	}
	
	public final void write(String line) throws IOException {
		writer.write(line);		
	}
	

	public final ILineReader getReader() {
		return reader;
	}

	public final ILineWriter getWriter() {
		return writer;
	}

	public final int inputAvailable() throws IOException {
		return reader.inputAvailable();
	}

	public long getLastReadTime() {
		
		return reader.getLastReadTime();
	}

	public long getBytesOut() {
		return writer.getBytesOut();
	}

	public long getLastWriteTime() {
		return writer.getLastWriteTime();
	}
	

}

