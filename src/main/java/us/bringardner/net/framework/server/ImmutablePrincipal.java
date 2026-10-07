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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A read only view of an authenticated principal, given to sessions by 
 * {@link Server#authenticate(String, byte[])} so a session can't change the access control 
 * list's user (its permissions, parameters or password). Only the state can be set.
 */
public class ImmutablePrincipal implements IPrincipal {

	private final IPrincipal target;
	private volatile State state = State.Authenticated;
	
	/**
	 * @param tmp the principal to wrap (not null)
	 */
	public ImmutablePrincipal (IPrincipal tmp) {
		this.target = java.util.Objects.requireNonNull(tmp, "principal");
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
