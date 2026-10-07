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

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.net.ssl.SSLSession;

import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IPrincipal;

/**
 * A non-blocking connection, the NIO counterpart of the framework's IConnection.
 * <p>
 * Nothing here blocks: writes are queued and sent by the connection's reactor thread, and
 * input arrives as frames (see {@link IFrameDecoder}) passed to the connection's
 * {@link INioHandler}. All methods may be called from any thread.
 *
 * @author Tony Bringardner
 */
public interface INioConnection extends Closeable {

	/**
	 * @return a number unique to this connection within the JVM
	 */
	public long getId();

	public SocketAddress getRemoteAddress();
	public SocketAddress getLocalAddress();

	/**
	 * @return true until the connection is closed
	 */
	public boolean isOpen();

	/**
	 * @return true if this end made the connection ({@link NioClient}), false if a server accepted it
	 */
	public boolean isClientMode();

	/**
	 * @return the server that accepted this connection, or null for a client connection
	 */
	public NioServer getServer();

	/**
	 * @return the handler that receives this connection's events
	 */
	public INioHandler getHandler();

	/**
	 * Queue data to be sent. The buffer (position to limit) now belongs to the connection,
	 * the caller must not change it after this call.
	 *
	 * @param data the bytes to send
	 * @throws IOException if the connection is closed (or closing)
	 */
	public void write(ByteBuffer data) throws IOException;

	/**
	 * Queue a copy of the bytes to be sent.
	 * @throws IOException if the connection is closed (or closing)
	 */
	public default void write(byte[] data) throws IOException {
		write(ByteBuffer.wrap(data.clone()));
	}

	/**
	 * Queue a line (UTF-8) followed by CR LF, e.g. the SSH identification string.
	 * @throws IOException if the connection is closed (or closing)
	 */
	public default void writeLine(String line) throws IOException {
		write(ByteBuffer.wrap((line+"\r\n").getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * @return bytes queued by write() and not yet sent. Use it to stop producing data
	 * for a slow peer.
	 */
	public long getPendingWriteBytes();

	/**
	 * Close at once, data not yet sent is dropped. Calling it more than once does nothing.
	 */
	@Override
	public void close();

	/**
	 * Close once all the data already queued has been sent. Later writes are refused.
	 */
	public void closeAfterFlush();

	/**
	 * Stop reading from the peer (flow control): the peer is slowed by TCP once the
	 * socket buffers fill. Frames already read are still delivered.
	 */
	public void pauseReading();

	/**
	 * Start reading again after {@link #pauseReading()}.
	 */
	public void resumeReading();

	public boolean isReadingPaused();

	/**
	 * Switch to TLS, the NIO counterpart of the framework's negotiateSecureSocket (STARTTLS, 
	 * AUTH TLS...). Data queued before this call is sent in clear text, data after it 
	 * encrypted, so a server replies (e.g. "220 Ready to start TLS") and then calls this; 
	 * a client calls it when that reply arrives. Input not yet passed to the handler is 
	 * treated as TLS. Writes may go on at once, they are sent once the handshake is done.
	 * <p>
	 * The server's TLS settings (SecureBaseObject: key store...) or the client's (trust 
	 * managers, host name check) are used.
	 * 
	 * @throws IOException if the connection is closed or TLS isn't available (no SSLContext)
	 * @throws IllegalStateException if TLS has already started
	 */
	public void startTls() throws IOException;

	/**
	 * @return true once a TLS handshake has finished on this connection
	 */
	public boolean isSecure();

	/**
	 * @return the TLS session (peer certificates, cipher...), null if TLS has not started
	 */
	public SSLSession getSslSession();

	/**
	 * @return the decoder that splits the input into frames
	 */
	public IFrameDecoder getDecoder();

	/**
	 * Change how the input is split into frames. Takes effect with the next frame, so a
	 * handler can switch protocols in onMessage (SSH: a version line, then binary packets).
	 *
	 * @param decoder the new decoder (not null)
	 */
	public void setDecoder(IFrameDecoder decoder);

	/**
	 * @return the logged in user, null until authenticated
	 */
	public IPrincipal getPrincipal();

	/**
	 * @param principal the logged in user, e.g. from {@link NioServer#authenticate(String, byte[])}
	 */
	public void setPrincipal(IPrincipal principal);

	/**
	 * @return true if the logged in user may do the action (false if not logged in, 
	 * a client connection, or the server has no access control)
	 */
	public default boolean isAuthorized(IPermission action) {
		NioServer server = getServer();
		return server != null && server.isAuthorized(getPrincipal(), action);
	}

	public Object getAttribute(String name);

	/**
	 * Setting a null value removes the name.
	 */
	public void setAttribute(String name, Object value);
	public Object removeAttribute(String name);

	/**
	 * @return this connection's attributes (session values), a thread safe map
	 */
	public Map<String,Object> getAttributes();

	public long getBytesIn();
	public long getBytesOut();
	public long getConnectTime();
	public long getLastReadTime();
	public long getLastWriteTime();
}
