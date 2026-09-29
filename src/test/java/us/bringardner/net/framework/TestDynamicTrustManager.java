package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.client.DynamicTrustManager;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator.ManageAs;

/**
 * Unit tests without a network: an SSLEngine supplies the host, fake trust managers play the JVM trust store.
 * Each test uses its own host names because approvals are static (JVM wide).
 */
public class TestDynamicTrustManager {

	private static final String AUTH = "RSA";
	private static X509Certificate[] chain;
	private static File trustFile;

	private static final TrustManager[] SYSTEM_REJECTS = {new FakeSystem(false)};
	private static final TrustManager[] SYSTEM_ACCEPTS = {new FakeSystem(true)};

	private static class FakeSystem implements X509TrustManager {
		private final boolean accept;

		FakeSystem(boolean accept) {
			this.accept = accept;
		}

		private void check() throws CertificateException {
			if( !accept ) {
				throw new CertificateException("not in the (fake) trust store");
			}
		}

		public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
			check();
		}

		public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
			check();
		}

		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	@BeforeAll
	public static void setup() throws Exception {
		File home = ServerTestSupport.useTestHome();
		trustFile = new File(home, ".bjlTructed");
		chain = new X509Certificate[] {ServerTestSupport.certificate()};
	}

	private static SSLEngine engine(String host) throws Exception {
		return SSLContext.getDefault().createSSLEngine(host, 443);
	}

	private static CertificateValidator answer(ManageAs action) {
		return cert -> action;
	}

	private static String savedApprovals() throws Exception {
		return trustFile.exists() ? new String(Files.readAllBytes(trustFile.toPath()), StandardCharsets.UTF_8) : "";
	}

	@Test
	public void testSystemTrustDoesNotAsk() throws Exception {
		DynamicTrustManager tm = new DynamicTrustManager(SYSTEM_ACCEPTS, cert -> fail("should not ask"));
		assertDoesNotThrow(() -> tm.checkServerTrusted(chain, AUTH, engine("system.test")));
	}

	@Test
	public void testUnknownRejected() throws Exception {
		DynamicTrustManager tm = new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.REJECT));
		CertificateException e = assertThrows(CertificateException.class, () -> tm.checkServerTrusted(chain, AUTH, engine("reject.test")));
		assertTrue(e.getMessage().contains("reject.test"), e.getMessage());
	}

	@Test
	public void testValidatorIsGivenTheHost() throws Exception {
		AtomicReference<String> asked = new AtomicReference<>();
		DynamicTrustManager tm = new DynamicTrustManager(SYSTEM_REJECTS, new CertificateValidator() {
			public ManageAs validate(X509Certificate cert) {
				return ManageAs.REJECT;
			}

			public ManageAs validate(X509Certificate cert, String host) {
				asked.set(host);
				return ManageAs.REJECT;
			}
		});
		assertThrows(CertificateException.class, () -> tm.checkServerTrusted(chain, AUTH, engine("asked.test")));
		assertEquals("asked.test", asked.get());
	}

	@Test
	public void testAcceptOnceIsHostSpecificAndNotSaved() throws Exception {
		new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.ACCEPT_ONCE)).checkServerTrusted(chain, AUTH, engine("once.test"));

		DynamicTrustManager rejecting = new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.REJECT));
		assertDoesNotThrow(() -> rejecting.checkServerTrusted(chain, AUTH, engine("once.test")));
		assertThrows(CertificateException.class, () -> rejecting.checkServerTrusted(chain, AUTH, engine("other-once.test")));
		assertFalse(savedApprovals().contains("once.test|"));

		// An ACCEPT_ALWAYS save must not persist ACCEPT_ONCE approvals
		new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.ACCEPT_ALWAYS)).checkServerTrusted(chain, AUTH, engine("always-after-once.test"));
		assertFalse(savedApprovals().contains("\nonce.test|") || savedApprovals().startsWith("once.test|"));
		DynamicTrustManager.removeTrusted("once.test");
		DynamicTrustManager.removeTrusted("always-after-once.test");
	}

	@Test
	public void testAcceptAlwaysIsSavedAndRemovable() throws Exception {
		new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.ACCEPT_ALWAYS)).checkServerTrusted(chain, AUTH, engine("always.test"));
		assertTrue(savedApprovals().contains("always.test|"), savedApprovals());

		DynamicTrustManager rejecting = new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.REJECT));
		assertDoesNotThrow(() -> rejecting.checkServerTrusted(chain, AUTH, engine("always.test")));
		assertThrows(CertificateException.class, () -> rejecting.checkServerTrusted(chain, AUTH, engine("not-always.test")));

		assertEquals(1, DynamicTrustManager.removeTrusted("always.test"));
		assertThrows(CertificateException.class, () -> rejecting.checkServerTrusted(chain, AUTH, engine("always.test")));
		assertFalse(savedApprovals().contains("always.test|"));
	}

	@Test
	public void testHostIsCaseInsensitive() throws Exception {
		new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.ACCEPT_ONCE)).checkServerTrusted(chain, AUTH, engine("Mixed.Case.Test"));
		DynamicTrustManager rejecting = new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.REJECT));
		assertDoesNotThrow(() -> rejecting.checkServerTrusted(chain, AUTH, engine("mixed.case.test")));
		DynamicTrustManager.removeTrusted("mixed.case.test");
	}

	@Test
	public void testUnknownHostApprovalDoesNotCoverNamedHosts() throws Exception {
		// Two argument form: the host is not known
		new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.ACCEPT_ONCE)).checkServerTrusted(chain, AUTH);
		DynamicTrustManager rejecting = new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.REJECT));
		assertDoesNotThrow(() -> rejecting.checkServerTrusted(chain, AUTH));
		assertThrows(CertificateException.class, () -> rejecting.checkServerTrusted(chain, AUTH, engine("named.test")));
		DynamicTrustManager.removeTrusted(null);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testLegacyApprovalStillHonored() throws Exception {
		// Earlier versions keyed approvals by the certificate signature only
		Field field = DynamicTrustManager.class.getDeclaredField("trusted");
		field.setAccessible(true);
		Map<String, String> trusted = (Map<String, String>) field.get(null);
		String legacyKey = Base64.getEncoder().encodeToString(chain[0].getSignature());
		trusted.put(legacyKey, "legacy");
		try {
			DynamicTrustManager rejecting = new DynamicTrustManager(SYSTEM_REJECTS, answer(ManageAs.REJECT));
			assertDoesNotThrow(() -> rejecting.checkServerTrusted(chain, AUTH, engine("legacy-any-host.test")));
		} finally {
			trusted.remove(legacyKey);
		}
	}

	@Test
	public void testBadInput() throws Exception {
		DynamicTrustManager tm = new DynamicTrustManager(SYSTEM_ACCEPTS, answer(ManageAs.ACCEPT_ALWAYS));
		assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[0], AUTH));
		assertThrows(CertificateException.class, () -> tm.checkServerTrusted(null, AUTH));
		assertThrows(CertificateException.class, () -> tm.checkClientTrusted(chain, AUTH));
		assertThrows(CertificateException.class, () -> tm.checkClientTrusted(chain, AUTH, engine("client.test")));
	}

	@Test
	public void testDefaultTrustStoreLoads() {
		DynamicTrustManager tm = new DynamicTrustManager();
		assertTrue(tm.getAcceptedIssuers().length > 0, "JVM trust store has no CAs");
	}
}
