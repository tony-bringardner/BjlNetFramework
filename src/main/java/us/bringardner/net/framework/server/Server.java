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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import us.bringardner.core.ILogger.Level;
import us.bringardner.core.util.AbstractCoreServer;
import us.bringardner.net.framework.IConnection;
import us.bringardner.net.framework.IConnectionFactory;
import us.bringardner.net.framework.IProcessor;
import us.bringardner.net.framework.IProcessorFactory;

public class Server extends AbstractCoreServer implements IServer {

	/**
	 * 
	 */
	protected static final long serialVersionUID = 1L;
	public static final int DEFAULT_BUFFER_SIZE = 1024*1024;
	public static final int DEFAULT_ACCEPT_TIMEOUT = 5000;
	public static final int DEFAULT_CONNECTION_TIMEOUT = 60000;
	//  Max idle connection 24 hr
	public static final long DEFAULT_MAX_IDEL_CONNECTION = 1000*60*60*24;
	//  Default admin freq = 5min
	private static final long DEFAULT_ADMIN_REFQ = 1000*60*5;

	private static int defaultAcceptTimeout = DEFAULT_ACCEPT_TIMEOUT;
	private static int defaultConnectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	private static Level defaultLogLevel = Level.NONE;

	private int bufferSize =DEFAULT_BUFFER_SIZE;
	private int port;
	private boolean secure = false;
	private volatile ServerSocketFactory serverSocketFactory;
	private IProcessorFactory processorFactory;

	private IConnectionFactory connectionFactory;
	private int acceptTimeout     = getDefaultAcceptTimeout();
	private long maxIdleConnection = DEFAULT_MAX_IDEL_CONNECTION;
	private long adminFreq = DEFAULT_ADMIN_REFQ;
	private long lastAdmin=0;

	private Map<String,Object> runtimeValues = new HashMap<String, Object>();
	private Map<Socket, IProcessor> activeClients = new WeakHashMap<Socket, IProcessor>();
	private ServerSocket svr = null;
	private boolean debug;
	private IAccessControlList accessControl;
	private String serverGreating;



	public static Level getDefaultLogLevel() {
		return defaultLogLevel;
	}

	public static void setDefaultLogLevel(Level level) {
		defaultLogLevel = level;
	}

	public String getServerGreating() {
		return serverGreating;
	}

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

	
	public  SocketFactory getSocketFactory(boolean isSecure) {
		SocketFactory ret = null;

		if (isSecure) {
			try {
				// set up key manager to do server authentication
				SSLContext ctx=getSSLContext();
				if( ctx != null ) {
					ret = ctx.getSocketFactory();
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			ret =  SocketFactory.getDefault();
		}

		return ret;

	}

	public SSLContext getSSLContext(String sslOrTls) throws IOException {
		
		SSLContext ret=null;
		String tmp = getProtocol();
		
		try {
			super.setProtocol(sslOrTls);	
		} finally {					
			setProtocol(tmp);
		}
		
		
		ret =  super.getSSLContext();
		
		
		
		return ret;
	}

	
	public SSLContext getSSLContext(String sslOrTls, String instanceName, String keyStoreType, String passPhrase, String keyFileName) throws IOException {

		SSLContext ret;
		KeyManagerFactory kmf;
		KeyStore ks;
		//String name = getName();
		try {

			ret = SSLContext.getInstance(sslOrTls);

			kmf = KeyManagerFactory.getInstance(instanceName);
			ks = KeyStore.getInstance(keyStoreType);

			char[] passphrase = passPhrase.toCharArray();

			File f = new File(keyFileName);

			if( f.exists() == false) {
				throw new IllegalArgumentException("Key file not found ("+f+")");
			}

			ks.load(new FileInputStream(f), passphrase);


			kmf.init(ks, passphrase);

			ret.init(kmf.getKeyManagers(), null, null);
		} catch (Exception e) {
			throw new IOException(e);
		}


		return ret;

	}

	public ServerSocketFactory getServerSocketFactory(boolean isSecure) 
	{
		ServerSocketFactory ret = null;

		if (isSecure) {
			try {
				// set up key manager to do server authentication
				SSLContext ctx=getSSLContext();
				if( ctx != null ) {
					ret = ctx.getServerSocketFactory();
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			ret =  ServerSocketFactory.getDefault();			
		}

		return ret;
	}

	public int getBufferSize() {
		return bufferSize;
	}

	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
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
			return;
		}
		if(getProcessorFactory() == null ) {
			logError("No processor factory defined.");
			return;
		}

		// 2> Create the server socket
		if( svr == null ) {
			try {
				svr = getServerSocketFactory().createServerSocket(getPort());
				svr.setSoTimeout(getAcceptTimeout());
			} catch (IOException e) {
				logError("Can't create ServerSockt",e);
			}
		}

		if( svr != null ) {
			started = running = true;
			lastAdmin = System.currentTimeMillis();

			logInfo("Server "+getName()+" is running on port "+getPort()+".");
			while( !stopping ) {

				// 3>  Listen for connections.
				Socket socket;
				try {
					socket = svr.accept();
					logDebug("Incomming conenction from "+socket);
					//  Start the processing
					IConnection conn = null;
					conn = getConnectionFactory().getConnection(socket);

					try {

						if( serverGreating != null && !serverGreating.isEmpty()) {
							conn.writeLine(serverGreating);
						}
						IProcessor proc = getProcessor();
						proc.setConnection(conn);
						activeClients.put(socket,proc);
						proc.start();

					} catch (InstantiationException e) {
						logError("Can't create Processor", e);
					} catch (IllegalAccessException e) {
						logError("Can't create Processor", e);
					}



				} catch (SocketTimeoutException e) {
					// Ignore these
				} catch (IOException e) {
					// Report the error
					logError("Error in server run",e);
				}
				if( (System.currentTimeMillis()-lastAdmin) > adminFreq) {
					doAdmin();
				}
			}

			//  We're done so close the serverSocket and exit.
			try {
				svr.close();
			} catch (IOException e) {
				logError("Error closing serverSocket",e);
			}
		}

		running = false;
		logInfo("Server "+getName()+" has stopped.");
	}


	protected void doAdmin() {
		//  Check for idle connections

		Map<Socket, IProcessor> clients = getActiveClients();
		lastAdmin = System.currentTimeMillis();
		for (Iterator<Socket> it = clients.keySet().iterator(); it.hasNext();) {
			Socket sock = (Socket) it.next();
			IProcessor element = (IProcessor) clients.get(sock);
			IConnection con = element.getConnection();
			if((lastAdmin-con.getLastReadTime()) > maxIdleConnection  && (lastAdmin-con.getLastWriteTime()) > maxIdleConnection) {
				it.remove();
				con.close();				
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


	public void setRuntimeValues(Map<String, Object> runtimeValues) {
		this.runtimeValues = runtimeValues;
	}

	public Object getRuntimeValue(String name) {
		return getRuntimeValues().get(name);
	}

	public void setRuntimeValue(String name, Object value) {
		getRuntimeValues().put(name, value);
	}

	public Object removeRuntimeValue(String name) {
		return getRuntimeValues().remove(name);
	}

	public void removeClient(IProcessor processor) {
		IConnection con = processor.getConnection();
		if( con != null ) {
			Socket sock = con.getSocket();
			if( sock != null ) {
				activeClients.remove(sock);
			}
		}		
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
			ret = acl.getPrincipal(user, credentials);
		}
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.framework.IServer#isAuthorized(java.security.Principal, IPermission)
	 */
	public boolean isAuthorized(IPrincipal user, IPermission action) {
		boolean ret = false;
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
		if( accessControl == null ) {
			synchronized (this) {
				if( accessControl == null ) {
					String tmp = getProperty(AUTHENTICATOION_PROVIDER_PROPERTY);
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
	}


}
