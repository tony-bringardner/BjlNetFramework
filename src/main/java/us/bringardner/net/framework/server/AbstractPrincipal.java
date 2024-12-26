package us.bringardner.net.framework.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A concrete implementation of a AbstractPrincipal *a.k.a. User)
 */
public abstract class AbstractPrincipal implements IPrincipal {

	String name;
	List<IPermission> permissions = new ArrayList<>();
	Map<Object,Object> parameters = new HashMap<>();
	State state= State.Unvalidated;
	byte [] credentials;
	
	
	public AbstractPrincipal(String name) {
		this.name  = name;
	}
	
	public void add(IPermission permission) {
		permissions.add(permission);
	}
	
	public boolean hasPermission(IPermission permision) {
		for(IPermission p : permissions) {
			if( p.equals(permision)) {
				return true;
			}
		}
		
		return false;
	}
	
	public boolean remove(IPermission permission) {
		return permissions.remove(permission);
	}
	
	public List<IPermission> getPermisssions() {
		List<IPermission> ret = new ArrayList<>();
		ret.addAll(permissions);		
		return ret;
	}
	
	public void setPermissions(List<IPermission> permissions) {
		this.permissions.clear();
		this.permissions.addAll(permissions);
	}
	
	public Object getParameter(Object key) {
		return parameters.get(key);
	}
	
	public void setParameter(Object key, Object value) {
		parameters.put(key,value);
	}

	@Override
	public Map<Object, Object> getParameters() {
		Map<Object, Object>  ret = new HashMap<>();
		ret.putAll(parameters);
		return ret;
	}

	@Override
	public void setParameters(Map<Object, Object> parameters) {
		parameters.clear();
		parameters.putAll(parameters);
		
	}
	
	@Override
	public Object removeParameter(Object key) {
		return parameters.remove(key);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public void setState(State state) {
		 this.state = state;		
	}

	@Override
	public byte[] getCredentials() {
		return credentials;
	}

	@Override
	public void setCredentials(byte [] credentials) {
		this.credentials = credentials;		
	}

}
