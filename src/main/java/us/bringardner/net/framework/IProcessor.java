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
package us.bringardner.net.framework;


import java.util.Map;

import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IPrincipal;
import us.bringardner.net.framework.server.IServer;






/**
 * Objects that implement this Interface know how
 * to process client connections accepted by a server.
 * 
 * 
 * @author: Tony Bringardner
 */
public interface IProcessor extends IManagedThread {
	
	/**
	 * Set the Server that accepted this connection.
	 * 
	 * @param server
	 */
	public void setServer(IServer server);
	
	/**
	 * @return The Server that accepted this connection.
	 */
	public IServer getServer();

	/**
	 *  Process an incoming connection.
	 *  
	 * @param connection
	 */
	public void setConnection(IConnection connection);
	
	/**
	 * @return the current connection
	 */
	public IConnection getConnection();
	
	
	public Map<String,Object> getSessionValues() ;
	public void setSessionValue(String name, Object value) ;
	public Object getSessionValue(String name) ;
	public Object removeSessionValue(String name);
	
	public Object removeServerRuntimeValue(String name) ;
	public Map<String,Object> getServerRuntimeValues() ;
	public void setServerRuntimeValue(String name, Object value) ;
	public Object getServerRuntimeValue(String name);

	public IPrincipal getPrincipal();
	public void setPrincipal(IPrincipal  user);
	
	public boolean isAuthorized(IPermission action);
	
	
}