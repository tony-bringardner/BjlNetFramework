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

import java.util.HashMap;
import java.util.Map;

import us.bringardner.core.BaseThread;
import us.bringardner.net.framework.IConnection;
import us.bringardner.net.framework.IProcessor;


public abstract class AbstractProcessor extends BaseThread implements IProcessor {

	private IConnection connection;
	private IServer server;
	private Map<String, Object> sessionValues = new HashMap<String, Object>();
	private IPrincipal principal;
	
	public AbstractProcessor() {
		getLogger().setLevel(Server.getDefaultLogLevel());
	}

	
	public IServer getServer() {
		return server;
	}

	
	
	public IPrincipal getPrincipal() {
		return principal;
	}



	public void setPrincipal(IPrincipal principal) {
		this.principal = principal;
	}



	/*
	 * @see us.bringardner.net.framework.IProcessor#isAuthorized(IPermission)
	 */
	public boolean isAuthorized(IPermission action) {
		return getServer().isAuthorized(getPrincipal(), action);
	}

	public void setServer(IServer server) {
		setSecure(server.isSecure());
		this.server = server;
	}

	public void setConnection(IConnection connection) {
		this.connection = connection;
	}

	public IConnection getConnection() {
		return connection;
	}

	public Map<String, Object> getSessionValues() {
		return sessionValues;
	}

	public void setSessionValues(Map<String, Object> sessionValues) {
		this.sessionValues = sessionValues;
	}
	
	public void setSessionValue(String name, Object value) {
		sessionValues.put(name, value);
	}
	
	public Object getSessionValue(String name) {
		return sessionValues.get(name);
	}
	
	public Object removeSessionValue(String name) {
		return sessionValues.remove(name);
	}
	

	public void setServerRuntimeValue(String name, Object value) {
		getServer().setRuntimeValue(name, value);
	}
	
	public Object getServerRuntimeValue(String name) {
		return getServer().getRuntimeValue(name);
	}

	public Object removeServerRuntimeValue(String name) {
		return getServer().removeRuntimeValue(name);
	}
	
	public Map<String, Object> getServerRuntimeValues() {
		return getServer().getRuntimeValues();
	}
	
	public String getThreadName() {
		return getName();
	}

}
