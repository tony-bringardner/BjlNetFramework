package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import us.bringardner.net.framework.server.DefaultRequestContext;

public class TestRequestContext {

	@Test
	public void testTokensMatchSplit() {
		String [] lines = {"", " ", "   ", "a", "a b", " a b", "a  b", "a b ", "a b   ", "  a  b  c  "};
		for (String line : lines) {
			DefaultRequestContext ctx = new DefaultRequestContext(line);
			assertArrayEquals(line.split(" "), ctx.getTokens(), "tokens for '"+line+"'");
		}
	}

	@Test
	public void testRemainingIsExact() {
		DefaultRequestContext ctx = new DefaultRequestContext("STOR my  file.txt  ");
		assertEquals("STOR", ctx.getNextToken());
		assertEquals("my  file.txt  ", ctx.getRemainingTokens());
		assertFalse(ctx.hasNext());
		assertNull(ctx.getRemainingTokens());

		ctx = new DefaultRequestContext("Echo text1");
		assertEquals("Echo", ctx.getFirstToken());
		assertEquals("text1", ctx.getRemainingTokens());

		ctx = new DefaultRequestContext("NOOP");
		assertEquals("NOOP", ctx.getNextToken());
		assertNull(ctx.getRemainingTokens());
	}

	@Test
	public void testLiteralSeparator() {
		DefaultRequestContext ctx = new DefaultRequestContext("a|b|c");
		ctx.setSeparator("|");
		assertArrayEquals(new String[] {"a","b","c"}, ctx.getTokens());
		assertEquals("a", ctx.getNextToken());
		assertEquals("b|c", ctx.getRemainingTokens());
	}

	@Test
	public void testRegexSeparator() {
		DefaultRequestContext ctx = new DefaultRequestContext("a \t b   c");
		ctx.setSeparator("\\s+");
		assertArrayEquals(new String[] {"a","b","c"}, ctx.getTokens());
		ctx.getNextToken();
		// previously the regex itself was inserted between the tokens
		assertEquals("b   c", ctx.getRemainingTokens());
	}

	@Test
	public void testChangingLineResets() {
		DefaultRequestContext ctx = new DefaultRequestContext("a b");
		assertEquals("a", ctx.getNextToken());
		ctx.setCommandLine("x y");
		assertEquals("x", ctx.getNextToken());
	}
}
