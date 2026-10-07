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
 * Frames that start with their length: a big endian unsigned integer of 1, 2 or 4 bytes
 * giving the number of bytes after it. With 4 bytes this is the SSH binary packet
 * (uint32 packet_length, RFC 4253 section 6) before encryption starts.
 *
 * @author Tony Bringardner
 */
public class LengthFieldFrameDecoder implements IFrameDecoder {

	/** RFC 4253 says an implementation must handle packets of 35000 bytes; leave room for larger ones. */
	public static final int DEFAULT_MAX_FRAME_LENGTH = 256*1024;

	private final int lengthFieldSize;
	private final int maxFrameLength;
	private final boolean stripLength;

	/**
	 * A 4 byte length, frames up to {@link #DEFAULT_MAX_FRAME_LENGTH}, the length is kept in the frame.
	 */
	public LengthFieldFrameDecoder() {
		this(4, DEFAULT_MAX_FRAME_LENGTH, false);
	}

	/**
	 * @param lengthFieldSize 1, 2 or 4
	 * @param maxFrameLength the largest length accepted (not counting the length field)
	 * @param stripLength true to leave the length field out of the frames
	 */
	public LengthFieldFrameDecoder(int lengthFieldSize, int maxFrameLength, boolean stripLength) {
		if( lengthFieldSize != 1 && lengthFieldSize != 2 && lengthFieldSize != 4 ) {
			throw new IllegalArgumentException("lengthFieldSize must be 1, 2 or 4");
		}
		if( maxFrameLength <= 0 ) {
			throw new IllegalArgumentException("maxFrameLength must be > 0");
		}
		this.lengthFieldSize = lengthFieldSize;
		this.maxFrameLength = maxFrameLength;
		this.stripLength = stripLength;
	}

	public int getLengthFieldSize() {
		return lengthFieldSize;
	}

	public int getMaxFrameLength() {
		return maxFrameLength;
	}

	public boolean isStripLength() {
		return stripLength;
	}

	@Override
	public ByteBuffer decode(ByteBuffer in) throws IOException {
		if( in.remaining() < lengthFieldSize ) {
			return null;
		}
		int pos = in.position();
		long len;
		switch (lengthFieldSize) {
		case 1: len = in.get(pos) & 0xffL; break;
		case 2: len = in.getShort(pos) & 0xffffL; break;
		default: len = in.getInt(pos) & 0xffffffffL; break;
		}
		if( len > maxFrameLength ) {
			throw new IOException("Frame length "+len+" is larger than the maximum "+maxFrameLength);
		}
		if( in.remaining() < lengthFieldSize+len ) {
			return null;
		}
		if( stripLength ) {
			in.position(pos+lengthFieldSize);
			return IFrameDecoder.copy(in, (int) len);
		}
		return IFrameDecoder.copy(in, lengthFieldSize+(int) len);
	}

	/**
	 * @return a buffer with a 4 byte length followed by the payload, ready to write
	 */
	public static ByteBuffer frame(byte[] payload) {
		ByteBuffer ret = ByteBuffer.allocate(4+payload.length);
		ret.putInt(payload.length);
		ret.put(payload);
		ret.flip();
		return ret;
	}
}
