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

/**
 * Splits the bytes read from a connection into frames (lines, packets...).
 * <p>
 * Each connection has its own decoder, so a decoder may keep state.
 * It is only called by one thread at a time.
 *
 * @author Tony Bringardner
 */
public interface IFrameDecoder {

	/**
	 * Take one frame from the input.
	 *
	 * @param in the bytes received and not yet decoded, from position to limit.
	 * On success, advance the position past the frame.
	 * If there is not a whole frame yet, return null; the position may be left anywhere
	 * between its start and the limit, the bytes not consumed are offered again with more
	 * input.
	 * @return the frame, a buffer the handler may keep (not a view of in), or null for not yet
	 * @throws IOException if the input is invalid (e.g. a frame is too long), which closes the connection
	 */
	public ByteBuffer decode(ByteBuffer in) throws IOException;

	/**
	 * @return a new buffer with a copy of the next len bytes of in, whose position moves past them
	 */
	public static ByteBuffer copy(ByteBuffer in, int len) {
		ByteBuffer src = in.duplicate();
		src.limit(src.position()+len);
		ByteBuffer ret = ByteBuffer.allocate(len);
		ret.put(src);
		ret.flip();
		in.position(in.position()+len);
		return ret;
	}
}
