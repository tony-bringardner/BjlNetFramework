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

import java.nio.ByteBuffer;

/**
 * Receives the events of a connection, the NIO counterpart of the framework's IProcessor.
 * <p>
 * Instead of a thread per connection reading in a loop, the handler is called when
 * something happens. For one connection the calls are never concurrent and arrive in order
 * (onConnect, onMessage..., onClose). They run on the reactor thread unless the server or
 * client has a handler executor; on the reactor thread a handler must not block, or every
 * connection of that reactor waits.
 * <p>
 * An exception thrown by a handler method is passed to {@link #onError(INioConnection, Throwable)}
 * and the connection is closed.
 *
 * @author Tony Bringardner
 */
public interface INioHandler {

	/**
	 * The connection is established (a server sends its greeting here).
	 */
	public default void onConnect(INioConnection connection) throws Exception {
	}

	/**
	 * A frame has arrived.
	 *
	 * @param connection the connection
	 * @param frame the frame, as returned by the connection's decoder; the handler owns it
	 */
	public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception;

	/**
	 * Nothing was read or written for the server's / client's max idle time. Called once per
	 * idle period. The default closes the connection.
	 */
	public default void onIdle(INioConnection connection) throws Exception {
		connection.close();
	}

	/**
	 * Something failed (I/O, decoding or a handler method). The connection is closed after this.
	 */
	public default void onError(INioConnection connection, Throwable error) {
	}

	/**
	 * The connection has closed, the last call for the connection.
	 */
	public default void onClose(INioConnection connection) {
	}
}
