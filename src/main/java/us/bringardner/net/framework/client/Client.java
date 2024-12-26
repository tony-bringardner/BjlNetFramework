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
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import us.bringardner.core.SecureBaseObject;
import us.bringardner.net.framework.Connection;




public class Client extends Connection implements IClient {

	private volatile SecureBaseObject context;
	private int port;
	private String host;
	private boolean connected;
	
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
	public boolean connect() {
		
			try {
				setSocket(getSocketFactory().createSocket(getHost(),getPort()));
				connected = true;
			} catch (UnknownHostException e) {
				logError("Can't Connect to "+getHost()+":"+getPort(),e);
			} catch (IOException e) {
				logError("Can't Connect to "+getHost()+":"+getPort(),e);			
			}
			
		
		return connected;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.net.impl.client.ConnectionI#close()
	 */
	public void close() {
		super.close();
		connected = false;
	}
	
	@Override
	public SSLContext getSSLContext(String sslOrTsl) throws IOException {
		
		if( context == null ) {
			synchronized (this) {
				if( context == null ) {
					SecureBaseObject tmp = new SecureBaseObject();
					TrustManager mgr = new X509TrustManager() {
						
						public X509Certificate[] getAcceptedIssuers() {
							return null;
						}
						
						public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
							//for (X509Certificate cert : chain) {
								//String name = cert.getSubjectX500Principal().getName();
								//Date startDate = cert.getNotBefore();
								//Date endDate = cert.getNotAfter();
							//}
						}
						
						public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
							//System.out.println("auth type="+authType+" chains size="+chain.length);
						}
					};
					tmp.setTrustManagers(new TrustManager[] {mgr});
					context = tmp;
				}
			}			
		}
		
		context.setProtocol(sslOrTsl);
		return context.getSSLContext();
	}



}
