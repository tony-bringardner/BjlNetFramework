package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.server.FileBasedAcl;
import us.bringardner.net.framework.server.FileBasedAcl.FileBasedPrincipal;
import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IPrincipal;
import us.bringardner.net.framework.server.IPrincipal.State;
import us.bringardner.net.framework.server.Permission;
import us.bringardner.net.framework.server.Server;

/**
 * AbstractPrincipal permissions / parameters, Server runtime values, authentication and authorization.
 */
public class TestPrincipalsAndValues {

	private static final IPermission A = new Permission("A");
	private static final IPermission B = new Permission("B");

	@Test
	public void testDuplicatesAndRevoke() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		p.add(A);
		p.add(new Permission("A"));
		assertEquals(1, p.getPermissions().size());
		assertTrue(p.remove(A));
		assertFalse(p.hasPermission(A), "a removed permission must be revoked");
		assertFalse(p.remove(A));
	}

	@Test
	public void testMatchesByNameAcrossImplementations() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		p.add(A);
		IPermission other = () -> "A";
		assertTrue(p.hasPermission(other));
		assertFalse(p.hasPermission(() -> "Z"));
	}

	@Test
	public void testNullsIgnored() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		p.add(null);
		assertTrue(p.getPermissions().isEmpty());
		assertFalse(p.hasPermission(null));
		assertFalse(p.remove(null));
	}

	@Test
	public void testSetPermissionsReplacesAndKeepsOrder() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		p.add(new Permission("old"));
		p.setPermissions(Arrays.asList(B, A, null, new Permission("B")));
		List<IPermission> perms = p.getPermissions();
		assertEquals(2, perms.size());
		assertEquals("B", perms.get(0).getName());
		assertEquals("A", perms.get(1).getName());
		assertFalse(p.hasPermission(new Permission("old")));
		p.setPermissions(null);
		assertTrue(p.getPermissions().isEmpty());
	}

	@Test
	public void testSetParameters() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		p.setParameter("old", "x");
		Map<Object, Object> params = new HashMap<>();
		params.put("k", "v");
		p.setParameters(params);
		assertEquals("v", p.getParameter("k"));
		assertNull(p.getParameter("old"));
		assertEquals(1, params.size(), "the caller's map must not be cleared");
		assertEquals("v", p.removeParameter("k"));
		assertNull(p.getParameter("k"));
	}

	@Test
	public void testState() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		assertEquals(State.Unvalidated, p.getState());
		p.setState(State.Authenticated);
		assertEquals(State.Authenticated, p.getState());
	}

	@Test
	public void testPermissionValueObject() {
		assertEquals(A, new Permission("A"));
		assertEquals(A.hashCode(), new Permission("A").hashCode());
		assertFalse(A.equals(B));
		assertFalse(A.equals("A"));
		assertEquals("A", A.toString());
		assertTrue(A.compareTo(B) < 0);
	}

	@Test
	public void testRuntimeValues() {
		Server svr = new Server(0, "ValuesServer");
		svr.setRuntimeValue("a", 1);
		assertEquals(1, svr.getRuntimeValue("a"));
		svr.setRuntimeValue("a", null);
		assertNull(svr.getRuntimeValue("a"), "a null value removes the entry");
		svr.setRuntimeValue(null, 1);
		assertNull(svr.getRuntimeValue(null));
		assertNull(svr.removeRuntimeValue(null));

		Map<String, Object> values = new HashMap<>();
		values.put("b", 2);
		values.put("c", null);
		svr.setRuntimeValues(values);
		assertEquals(2, svr.getRuntimeValue("b"));
		assertNull(svr.getRuntimeValue("c"));
		assertEquals(2, svr.removeRuntimeValue("b"));
		assertTrue(svr.getRuntimeValues().isEmpty());

		svr.setRuntimeValues(null);
		assertNotNull(svr.getRuntimeValues());
	}

	@Test
	public void testAuthenticateAndAuthorize() {
		String hash = FileBasedAcl.hashPassword("pw".toCharArray());
		Server svr = new Server(0, "AuthServer");
		svr.setAccessControl(ServerTestSupport.acl("hashed, " + hash + ", A", "plain, plainpw, B"));

		IPrincipal hashed = svr.authenticate("hashed", "pw".getBytes());
		assertNotNull(hashed);
		assertEquals(State.Authenticated, hashed.getState());
		assertEquals("hashed", hashed.getName());
		assertNotNull(svr.authenticate("plain", "plainpw".getBytes()));

		assertNull(svr.authenticate("hashed", "wrong".getBytes()));
		assertNull(svr.authenticate("nobody", "pw".getBytes()), "unknown user");
		assertNull(svr.authenticate("nobody", null));

		assertTrue(svr.isAuthorized(hashed, A));
		assertFalse(svr.isAuthorized(hashed, B));
		assertFalse(svr.isAuthorized(null, A), "not logged in");

		// The server hands out a read only view
		assertThrows(UnsupportedOperationException.class, () -> hashed.add(B));
		assertThrows(UnsupportedOperationException.class, () -> hashed.getCredentials());
		assertThrows(UnsupportedOperationException.class, () -> hashed.getPermissions().add(B));
	}

	@Test
	public void testNoAccessControl() {
		Server svr = new Server(0, "NoAclServer-" + System.nanoTime());
		assertNull(svr.authenticate("anyone", "pw".getBytes()));
		assertFalse(svr.isAuthorized(new FileBasedPrincipal("anyone"), A));
	}
}
