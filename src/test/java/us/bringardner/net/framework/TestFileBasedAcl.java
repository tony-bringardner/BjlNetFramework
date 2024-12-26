package us.bringardner.net.framework;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import us.bringardner.core.ILogger.Level;
import us.bringardner.net.framework.server.FileBasedAcl;
import us.bringardner.net.framework.server.IAccessControlList;
import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IPrincipal;
import us.bringardner.net.framework.server.IServer;
import us.bringardner.net.framework.server.PropertyAuthenticator;
import us.bringardner.net.framework.server.Server;

public class TestFileBasedAcl {

	@Test
	public void testPropertyAcl() {
		String serverName = "TestSetver";

		System.setProperty(IServer.AUTHENTICATOION_PROVIDER_PROPERTY, PropertyAuthenticator.class.getCanonicalName());
		System.setProperty(serverName+".user0","echoUser, password  , Echo|Login, key1=val1|key2=val2");
		System.setProperty(serverName+".user1","user1  , password   , one|two|three|four|five, key3=val3|key4=val4");
		validateAcl(serverName);
	}
	
	
	@Test
	public void testFileBasedAcl () {
		String serverName = "TestSetver";

		System.setProperty(IServer.AUTHENTICATOION_PROVIDER_PROPERTY, FileBasedAcl.class.getCanonicalName());
		System.setProperty(serverName+".userFile","FileBasedAcl.txt");
		
		validateAcl(serverName);
	}

	private void validateAcl(String serverName) {
		Server svr = new Server(1000, serverName);
		svr.getLogger().setLevel(Level.WARN);
		IAccessControlList acl = svr.getAccessControl();
		assertNotNull(acl, "acl was not fond");
		assertTrue((acl instanceof FileBasedAcl), "acl is Not a FileBasedAcl"); 


		/*
			user1  , password   , one|two|three|four|five, key=val|key=val
			echoUser, password  , Echo|Login, key1=val1|key2=val2

		 */
		byte[] password = "password".getBytes();

		IPrincipal p = acl.getPrincipal("user1", password);
		assertNotNull(p, "user1 was not authenticated");

		List<IPermission> perms = p.getPermisssions();
		assertNotNull(perms, "permissionlist is null");
		String [] tmp1 = {"one","two","three","four","five"};
		assertEquals(tmp1.length, perms.size(),"Wrong permission size");
		for (int idx = 0; idx < tmp1.length; idx++) {
			assertEquals(tmp1[idx], perms.get(idx).getName(),"Permision "+idx+" is wrong");
		}
		Map<Object, Object> map = p.getParameters();
		assertEquals("val3", map.get("key3"),"Wrong parameter value for key3");
		assertEquals("val4", map.get("key4"),"Wrong parameter value for key4");


		p = acl.getPrincipal("echoUser", password);
		assertNotNull(p, "echoUser was not authenticated");

		perms = p.getPermisssions();
		assertNotNull(perms, "permissionlist is null");
		String [] tmp = {"Echo","Login"};
		assertEquals(tmp.length, perms.size(),"Wrong permission size");
		for (int idx = 0; idx < tmp.length; idx++) {
			assertEquals(tmp[idx], perms.get(idx).getName(),"Permision "+idx+" is wrong");
		}
		map = p.getParameters();
		assertEquals("val1", map.get("key1"),"Wrong parameter value for key1");
		assertEquals("val2", map.get("key2"),"Wrong parameter value for key2");

		
	}

}
