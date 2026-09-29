package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.server.FileBasedAcl;
import us.bringardner.net.framework.server.FileBasedAcl.FileBasedPrincipal;

public class TestPasswordHash {

	@Test
	public void testHashRoundTrip() {
		String hash = FileBasedAcl.hashPassword("s3cret".toCharArray());
		assertTrue(hash.startsWith(FileBasedAcl.HASH_PREFIX));
		assertTrue(FileBasedAcl.verifyPassword("s3cret".toCharArray(), hash));
		assertFalse(FileBasedAcl.verifyPassword("wrong".toCharArray(), hash));
		// salted, so the same password hashes differently
		assertNotEquals(hash, FileBasedAcl.hashPassword("s3cret".toCharArray()));
	}

	@Test
	public void testPrincipalHashedAndPlain() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");

		p.setCredentials(FileBasedAcl.hashPassword("pw".toCharArray()).getBytes(StandardCharsets.UTF_8));
		assertTrue(p.authenticate("pw".getBytes(StandardCharsets.UTF_8)));
		assertFalse(p.authenticate("px".getBytes(StandardCharsets.UTF_8)));

		p.setCredentials("plain".getBytes(StandardCharsets.UTF_8));
		assertTrue(p.authenticate("plain".getBytes(StandardCharsets.UTF_8)));
		assertFalse(p.authenticate("plaiN".getBytes(StandardCharsets.UTF_8)));
		assertFalse(p.authenticate(null));
	}
}
