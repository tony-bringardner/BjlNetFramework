package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.client.CertificateValidatorDialog;
import us.bringardner.net.framework.client.CertificateValidotorDialog;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.SingleCommandResponse;
import us.bringardner.net.framework.server.DefaultRequestContext;
import us.bringardner.net.framework.server.FileBasedAcl.FileBasedPrincipal;
import us.bringardner.net.framework.server.IServer;
import us.bringardner.net.framework.server.Permission;
import us.bringardner.net.framework.server.Server;

/**
 * The misspelled 0.x names are deprecated in 1.0 but must keep working until 2.0.
 */
@SuppressWarnings("deprecation")
public class TestDeprecatedNames {

	@Test
	public void testConstants() {
		assertEquals(IGenericResponseCode.REPLY_300_GENERIC_TEMPORARY_OK, IGenericResponseCode.REPLY_300_GENERIC_TEMPOARY_OK);
		assertEquals(IGenericResponseCode.REPLY_400_GENERIC_TEMPORARY_ERROR, IGenericResponseCode.REPLY_400_GENERIC_TEMPOARY_ERROR);
		assertEquals(IServer.AUTHENTICATION_PROVIDER_PROPERTY, IServer.AUTHENTICATOION_PROVIDER_PROPERTY);
		assertEquals(Server.DEFAULT_MAX_IDLE_CONNECTION, Server.DEFAULT_MAX_IDEL_CONNECTION);
	}

	@Test
	public void testPermissions() {
		FileBasedPrincipal p = new FileBasedPrincipal("user");
		p.add(new Permission("A"));
		assertEquals(p.getPermisssions(), p.getPermissions());
	}

	@Test
	public void testClientSeparator() {
		CommandClient client = new CommandClient();
		client.setSeperator("::");
		assertEquals("::", client.getSeparator());
		client.setSeparator("|");
		assertEquals("|", client.getSeperator());
	}

	@Test
	public void testRequestContextSeparator() {
		DefaultRequestContext ctx = new DefaultRequestContext("a|b");
		ctx.setSeperator("|");
		assertEquals("|", ctx.getSeparator());
		assertEquals("a", ctx.getNextToken());

		String saved = DefaultRequestContext.getDefaultSeparator();
		try {
			DefaultRequestContext.setDefaultSeperator(",");
			assertEquals(",", DefaultRequestContext.getDefaultSeparator());
			DefaultRequestContext.setDefaultSeparator(";");
			assertEquals(";", DefaultRequestContext.getDefaultSeperator());
		} finally {
			DefaultRequestContext.setDefaultSeparator(saved);
		}
	}

	@Test
	public void testGreeting() {
		Server svr = new Server(0, "GreetingNames");
		svr.setServerGreating("220 old");
		assertEquals("220 old", svr.getServerGreeting());
		svr.setServerGreeting("220 new");
		assertEquals("220 new", svr.getServerGreating());
	}

	@Test
	public void testReadResonse() throws Exception {
		SingleCommandResponse resp = new SingleCommandResponse();
		resp.readResonse(TestResponses.stub(" ", "200 ok"));
		assertEquals(200, resp.getResponseCode());
	}

	@Test
	public void testDialogClassKept() {
		assertTrue(CertificateValidatorDialog.class.isAssignableFrom(CertificateValidotorDialog.class));
	}
}
