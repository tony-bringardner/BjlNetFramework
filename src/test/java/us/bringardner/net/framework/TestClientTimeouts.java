package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.client.Client;
import us.bringardner.net.framework.client.CommandClient;

public class TestClientTimeouts {

	private static final String READ = CommandClient.class.getName()+"."+Client.PROPERTY_READ_TIMEOUT;
	private static final String CONNECT = CommandClient.class.getName()+"."+Client.PROPERTY_CONNECT_TIMEOUT;

	@Test
	public void testDefaults() {
		try(CommandClient client = new CommandClient("localhost", 1)) {
			assertEquals(Client.getDefaultReadTimeout(), client.getTimeout());
			assertEquals(Client.DEFAULT_CONNECT_TIMEOUT, client.getConnectTimeout());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testProperties() throws Exception {
		System.setProperty(READ, "1234");
		System.setProperty(CONNECT, "567");
		try(CommandClient client = new CommandClient("localhost", 1)) {
			assertEquals(1234, client.getTimeout());
			assertEquals(567, client.getConnectTimeout());

			// explicit calls win
			client.setTimeout(0);
			assertEquals(0, client.getTimeout());
		} finally {
			System.clearProperty(READ);
			System.clearProperty(CONNECT);
		}
	}

	@Test
	public void testInvalidPropertyUsesDefault() throws Exception {
		System.setProperty(READ, "five minutes");
		try(CommandClient client = new CommandClient("localhost", 1)) {
			assertEquals(Client.getDefaultReadTimeout(), client.getTimeout());
		} finally {
			System.clearProperty(READ);
		}
	}
}
