package us.bringardner.net.framework.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.core.BaseObject;
import us.bringardner.net.framework.server.IPrincipal.State;


public class FileBasedAcl extends BaseObject implements IAccessControlList {

	public static String PROP_FILE_NAME = "userFile";

	public static class FileBasedPrincipal extends AbstractPrincipal {

		public FileBasedPrincipal(String name) {
			super(name);			
		}

		@Override
		public boolean authenticate(byte[] credentials) {
			byte mine [] = getCredentials();
			boolean ret = mine.length == credentials.length;
			if(ret ) {
				for (int idx = 0; idx < mine.length; idx++) {
					if(!(ret = (mine[idx] == credentials[idx]))) {
						break;
					}
				}
			}

			return ret;
		}
	}

	private static class ImutableFIleBasedPrincipal implements IPrincipal {

		IPrincipal target ;

		ImutableFIleBasedPrincipal (IPrincipal tmp) {
			this.target = tmp;
		}


		@Override
		public boolean authenticate(byte[] credentials) {
			throw new UnsupportedOperationException("This is an imutable principal");
		}

		@Override
		public State getState() {
			return target.getState();
		}

		@Override
		public void setState(State state) {
			throw new UnsupportedOperationException("This is an imutable principal");			
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
			return Collections.unmodifiableList(target.getPermisssions());
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

	private Map<String , IPrincipal> users = new HashMap<>();

	public FileBasedAcl() {
		super();					
	}

	@Override
	public void initialize(IServer server) throws IOException {
		setPropertyPrefix(server.getName());

		String path = getProperty(PROP_FILE_NAME);
		if( path == null ) {
			throw new IOException("Can't find a property for "+PROP_FILE_NAME+" ");
		}

		// first try to get file from path
		InputStream in = getClass().getResourceAsStream(path);
		if( in == null ) {
			in = getClass().getResourceAsStream("/"+path);
		}
		if( in == null ) {
			File file = new File(path).getCanonicalFile();
			if(!file.exists() || !file.canRead()) {
				throw new IOException(path+" is not a valid file");
			}
			in = new FileInputStream(file);
		}


		load(in);		
	}

	private void load(InputStream in) throws IOException {
		try {
			String text = new String(in.readAllBytes());
			for(String line : text.split("[\n]")) {
				line = line.trim();
				if( !line.isEmpty()&& !line.startsWith("#")) {
					parseLine(line);					
				}
			}
		} finally {
			try {
				in.close();
			} catch (Exception e) {
			}
		}
	}

	protected void parseLine(String line) {

		String parts[] = line.split("[,]");
		// need at least name and credentials
		if( parts.length < 3) {
			return ;
		}

		// CLean up parts
		for (int idx = 0; idx < parts.length; idx++) {
			parts[idx] = parts[idx].trim();
		}

		FileBasedPrincipal p = new FileBasedPrincipal(parts[0].trim());
		//user1  , password   , one|two|three|four|five, key=val|key=val
		p.setCredentials(parts[1].trim().getBytes());
		users.put(p.getName(),p);
		if( parts.length> 2) {
			if( !parts[2].isEmpty()) {
				String perms [] = parts[2].split("[|]");
				for(String p1 : perms) {
					p1 = p1.trim();
					if( !p1.isEmpty()) {
						p.add(new Permission(p1.trim()));
					}
				}
			}
			if( parts.length> 3) {
				if( !parts[3].isEmpty()) {
					String args [] = parts[3].split("[|]");
					for(String a1 : args) {
						String a2[] = a1.split("[=]");
						if( a2.length == 2) {
							p.setParameter(a2[0].trim(), a2[1].trim());
						}
					}	
				}
			}
		}			
	}

	@Override
	public boolean checkPermission(IPrincipal user, IPermission action) {
		boolean ret = false;
		if( user.getState() == State.Authenticated) {
			ret = user.hasPermission(action);
		}		
		return ret;
	}


	@Override
	public IPrincipal getPrincipal(String user, byte[] password) {
		IPrincipal ret = null;

		IPrincipal tmp = users.get(user);
		if( tmp != null ) {
			if(tmp.authenticate(password)) {
				tmp.setState(State.Authenticated);
				ret = new ImutableFIleBasedPrincipal(tmp);
			}
		}

		return ret;
	}



}
