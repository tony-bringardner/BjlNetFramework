package us.bringardner.net.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;

/**
 * Connection.writeLines (BJL-41): several lines, one write and one flush on the socket.
 * With auto flush on (the default) every writeLine is its own write, i.e. its own TCP
 * segment or TLS record.
 */
public class TestBatchedWrites {

	/** Counts what reaches the socket. */
	private static final class CountingOut extends OutputStream {
		final ByteArrayOutputStream data = new ByteArrayOutputStream();
		int writes;
		int flushes;

		@Override
		public void write(int b) {
			writes++;
			data.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			writes++;
			data.write(b, off, len);
		}

		@Override
		public void flush() {
			flushes++;
		}
	}

	private static final class FakeSocket extends Socket {
		final CountingOut out = new CountingOut();

		@Override
		public OutputStream getOutputStream() {
			return out;
		}

		@Override
		public InputStream getInputStream() {
			return new ByteArrayInputStream(new byte[0]);
		}
	}

	private static Connection connection(FakeSocket socket) throws IOException {
		return new Connection(socket, true) {
			@Override
			public SSLContext getSSLContext(String sslOrTsl) {
				return null;
			}
		};
	}

	private static final List<String> LINES = Arrays.asList("211-Features:", " MDTM", " MLST type*;size*;", " SIZE", "211 End");

	@Test
	public void writeLinesSendsOnce() throws Exception {
		FakeSocket socket = new FakeSocket();
		Connection con = connection(socket);
		con.writeLines(LINES);
		assertEquals(1, socket.out.writes, "writes reaching the socket");
		assertEquals(1, socket.out.flushes, "flushes");
		assertEquals(String.join("\r\n", LINES) + "\r\n", socket.out.data.toString(StandardCharsets.UTF_8.name()));
		assertTrue(con.isAutoFlush(), "auto flush is restored");
	}

	@Test
	public void writeLineStillFlushesEachLine() throws Exception {
		FakeSocket socket = new FakeSocket();
		Connection con = connection(socket);
		for (String line : LINES) {
			con.writeLine(line);
		}
		assertEquals(LINES.size(), socket.out.writes, "one write per line, as before");
	}
}
