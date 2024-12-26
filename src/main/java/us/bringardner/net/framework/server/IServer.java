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
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.net.framework.server;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;

import us.bringardner.net.framework.IConnectionFactory;
import us.bringardner.net.framework.IManagedThread;
import us.bringardner.net.framework.IProcessor;
import us.bringardner.net.framework.IProcessorFactory;

/**
 * 
 * @author Tony Bringardner
 *
 */


public interface IServer extends IManagedThread {
	public static final String AUTHENTICATOION_PROVIDER_PROPERTY = "AuthenticationProvider";
	
	public int getPort();
	public void setPort(int p);

	public IProcessorFactory getProcessorFactory();
	public void setProcessorFactory(IProcessorFactory processorFactory);
	
	public void setConnectionFactory(IConnectionFactory connectionFactory) ;
	public IConnectionFactory getConnectionFactory();
	
	public IProcessor getProcessor() throws InstantiationException, IllegalAccessException;
	
	public void setServerSocketFactory(ServerSocketFactory factory);
	public ServerSocketFactory getServerSocketFactory() throws IOException;
			
	public void setAcceptTimeout(int milliSeconds);
	public int getAcceptTimeout();
		
	public Object getRuntimeValue(String name) ;
	public void setRuntimeValue(String name, Object value) ;
	public Object removeRuntimeValue(String name);
	public Map<String,Object> getRuntimeValues();
	public void removeClient(IProcessor processor);
	public Map<Socket, IProcessor> getActiveClients();
	public boolean isSecure();
	public void setSecure(boolean b);
	
	/**
	 * @param channelSecure
	 * @return
	 */
	public SocketFactory getSocketFactory(boolean channelSecure);
	/**
	 * @param channelSecure
	 * @return
	 */
	public ServerSocketFactory getServerSocketFactory(boolean channelSecure);
	/**
	 * @param sslOrTsl
	 * @return
	 * @throws IOException 
	 */
	public SSLContext getSSLContext(String sslOrTsl) throws IOException;
	
	/**
	 * @param debug
	 */
	public void setDebug(boolean debug);
	
	public boolean isDebug();
	
	
	
	/**
	 * Authenticate a user with the given credentials (probably a password).
	 * If the user can not be authenticated, null is returned.
	 * 
	 * If the ACL is undefined, all actions are allowed.
	 * 
	 * @param user
	 * @param credentials (password)
	 * @return The an authenticated AbstractPrincipal or null
	 */
	public IPrincipal authenticate(String user, byte[] credentials ) ;
		
	
	/**
	 * @param user
	 * @param action
	 * @return true if the given user is authorized for the given action 
	 */
	boolean isAuthorized(IPrincipal user, IPermission action);
	
	/**
	 * 
	 * @return
	 */
	IAccessControlList getAccessControl();
	
	/**
	 * 
	 * @param acl
	 */
	void setAccessControl(IAccessControlList acl);
	
}
