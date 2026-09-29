package us.bringardner.net.framework.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * A concrete implementation of a AbstractPrincipal *a.k.a. User)
 */
public abstract class AbstractPrincipal implements IPrincipal {

	String name;
	// Keyed by permission name (the same test Permission.equals uses) so lookups are O(1),
	// duplicates are impossible and remove() really revokes. Insertion order is kept.
	Map<String,IPermission> permissions = new LinkedHashMap<>();
	Map<Object,Object> parameters = new HashMap<>();
	State state= State.Unvalidated;
	byte [] credentials;
	
	
	public AbstractPrincipal(String name) {
		this.name  = name;
	}
	
	private static String key(IPermission permission) {
		return permission == null ? null : permission.getName();
	}

	public void add(IPermission permission) {
		String key = key(permission);
		if( key != null ) {
			permissions.putIfAbsent(key, permission);
		}
	}
	
	public boolean hasPermission(IPermission permision) {
		String key = key(permision);
		return key != null && permissions.containsKey(key);
	}
	
	public boolean remove(IPermission permission) {
		String key = key(permission);
		return key != null && permissions.remove(key) != null;
	}
	
	public List<IPermission> getPermisssions() {
		return new ArrayList<>(permissions.values());
	}
	
	public void setPermissions(List<IPermission> permissions) {
		this.permissions.clear();
		if( permissions != null ) {
			for (IPermission p : permissions) {
				add(p);
			}
		}
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
		// The argument shadows the field, this previously cleared the caller's map and left ours unchanged.
		this.parameters.clear();
		if( parameters != null ) {
			this.parameters.putAll(parameters);
		}
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
