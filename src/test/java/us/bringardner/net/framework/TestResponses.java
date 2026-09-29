package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.client.ICommandClient;
import us.bringardner.net.framework.client.MultiLineCommandResponse;
import us.bringardner.net.framework.client.SingleCommandResponse;

/**
 * Reply parsing, fed from a stub client (no network).
 */
public class TestResponses {

	/** A client whose readLine() returns the given lines, then null (end of stream). */
	static ICommandClient stub(String separator, String... lines) {
		Deque<String> queue = new ArrayDeque<>(Arrays.asList(lines));
		return (ICommandClient) Proxy.newProxyInstance(ICommandClient.class.getClassLoader(),
				new Class<?>[] {ICommandClient.class}, (proxy, method, args) -> {
					switch (method.getName()) {
					case "readLine":
						return queue.poll();
					case "getSeperator":
					case "getSeparator":
						return separator;
					default:
						throw new UnsupportedOperationException(method.getName());
					}
				});
	}

	private static SingleCommandResponse single(String... lines) throws Exception {
		SingleCommandResponse ret = new SingleCommandResponse();
		ret.readResponse(stub(" ", lines));
		return ret;
	}

	private static MultiLineCommandResponse multi(String... lines) throws Exception {
		MultiLineCommandResponse ret = new MultiLineCommandResponse();
		ret.readResponse(stub(" ", lines));
		return ret;
	}

	@Test
	public void testPositive() throws Exception {
		SingleCommandResponse resp = single("200 all good");
		assertEquals(200, resp.getResponseCode());
		assertEquals("all good", resp.getResponseText());
		assertTrue(resp.isPositive());
		assertFalse(resp.isError());
		assertArrayEquals(new String[] {"200 all good"}, resp.getFullResponse());
		assertNotNull(resp.toString());
	}

	@Test
	public void testClassification() throws Exception {
		assertTrue(single("150 opening").isPositivePreliminary());
		assertTrue(single("350 need more").isPositiveIntermediate());
		assertTrue(single("450 try later").isTemporaryError());
		assertTrue(single("550 no").isError());

		SingleCommandResponse ok = single("250 ok");
		assertFalse(ok.isPositivePreliminary());
		assertFalse(ok.isPositiveIntermediate());
		assertFalse(ok.isTemporaryError());
	}

	@Test
	public void testCodeOnly() throws Exception {
		SingleCommandResponse resp = single("250");
		assertEquals(250, resp.getResponseCode());
		assertNull(resp.getResponseText());
	}

	@Test
	public void testMalformedCodeIsError() throws Exception {
		SingleCommandResponse resp = single("hello world");
		assertEquals(500, resp.getResponseCode());
		assertTrue(resp.isError());
		assertEquals("hello world", resp.getResponseText());
	}

	@Test
	public void testEndOfStream() throws Exception {
		SingleCommandResponse resp = single();
		assertEquals(500, resp.getResponseCode());
		assertEquals("500 Response is null", resp.getFullResponse()[0]);
	}

	@Test
	public void testMultiCharacterSeparator() throws Exception {
		SingleCommandResponse resp = new SingleCommandResponse();
		resp.readResponse(stub("::", "200::ok::more"));
		assertEquals(200, resp.getResponseCode());
		assertEquals("ok::more", resp.getResponseText());
	}

	@Test
	public void testNullSeparatorDefaultsToSpace() throws Exception {
		SingleCommandResponse resp = new SingleCommandResponse();
		resp.readResponse(stub(null, "200 ok"));
		assertEquals(200, resp.getResponseCode());
		assertEquals("ok", resp.getResponseText());
	}

	@Test
	public void testMultiLine() throws Exception {
		MultiLineCommandResponse resp = multi("250-first", "250-second", "250 last", "220 next reply");
		assertEquals(250, resp.getResponseCode());
		assertEquals("first", resp.getResponseText());
		assertArrayEquals(new String[] {"250-first", "250-second", "250 last"}, resp.getFullResponse());
	}

	@Test
	public void testMultiLineBareFinalCode() throws Exception {
		MultiLineCommandResponse resp = multi("211-status", "211");
		assertEquals(211, resp.getResponseCode());
		assertEquals(2, resp.getFullResponse().length);
	}

	@Test
	public void testMultiLineWithSingleLineReply() throws Exception {
		MultiLineCommandResponse resp = multi("220 ready");
		assertEquals(220, resp.getResponseCode());
		assertEquals("ready", resp.getResponseText());
		assertEquals(1, resp.getFullResponse().length);
	}

	@Test
	public void testMultiLineEndOfStream() throws Exception {
		assertEquals(500, multi("250-first", "250-second").getResponseCode());
		assertEquals(500, multi().getResponseCode());
	}

	@Test
	public void testMultiLineTooLong() throws Exception {
		MultiLineCommandResponse resp = new MultiLineCommandResponse();
		resp.setMaxLines(3);
		assertEquals(3, resp.getMaxLines());
		resp.readResponse(stub(" ", "250-a", "250-b", "250-c", "250-d", "250 e"));
		assertEquals(500, resp.getResponseCode());
	}

	@Test
	public void testMultiLineMalformedFirstLine() throws Exception {
		assertEquals(500, multi("abc-def").getResponseCode());
	}
}
