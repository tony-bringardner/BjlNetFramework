/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.net.framework.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import us.bringardner.io.LineTooLongException;

/**
 * Splits the input into lines ending in LF or CR LF. The frames don't include the line end.
 * A line over the limit throws bjl_io's LineTooLongException, as the framework's line readers do.
 *
 * @author Tony Bringardner
 */
public class LineFrameDecoder implements IFrameDecoder {

	/** Longest line accepted by default (bytes, not counting the line end) */
	public static final int DEFAULT_MAX_LINE_LENGTH = 64*1024;

	private final int maxLineLength;
	// How far past the position the last call looked for a LF, so a long line isn't scanned again
	private int scanned;

	public LineFrameDecoder() {
		this(DEFAULT_MAX_LINE_LENGTH);
	}

	/**
	 * @param maxLineLength longest line accepted (bytes, without the line end). Without a limit
	 * a peer could use up the input buffer by never sending a line end.
	 */
	public LineFrameDecoder(int maxLineLength) {
		if( maxLineLength <= 0 ) {
			throw new IllegalArgumentException("maxLineLength must be > 0");
		}
		this.maxLineLength = maxLineLength;
	}

	public int getMaxLineLength() {
		return maxLineLength;
	}

	@Override
	public ByteBuffer decode(ByteBuffer in) throws IOException {
		int start = in.position();
		int limit = in.limit();
		for (int i = start+scanned; i < limit; i++) {
			if( in.get(i) == '\n' ) {
				int len = i-start;
				if( len > 0 && in.get(i-1) == '\r' ) {
					len--;
				}
				if( len > maxLineLength ) {
					throw new LineTooLongException(maxLineLength);
				}
				scanned = 0;
				ByteBuffer ret = IFrameDecoder.copy(in, len);
				in.position(i+1);
				return ret;
			}
		}
		scanned = limit-start;
		// +1 for a CR that may be followed by the LF
		if( scanned > maxLineLength+1 ) {
			throw new LineTooLongException(maxLineLength);
		}
		return null;
	}

	/**
	 * @return the frame as a UTF-8 string
	 */
	public static String toString(ByteBuffer frame) {
		return StandardCharsets.UTF_8.decode(frame.duplicate()).toString();
	}
}
