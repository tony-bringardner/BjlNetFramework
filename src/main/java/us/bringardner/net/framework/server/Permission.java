package us.bringardner.net.framework.server;

public class Permission implements IPermission {

	String name;
	
	public Permission(String name) {
		this.name = name;
	}
	
	@Override
	public String getName() {
		return name;
	}
	
	@Override
	public int hashCode() {
		return name.hashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		boolean ret = false;
		if (obj instanceof IPermission) {
			 ret = ((IPermission) obj).getName().equals(getName());			
		}
		
		return ret;
	}

}
