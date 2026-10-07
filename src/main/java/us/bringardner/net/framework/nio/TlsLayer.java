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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import us.bringardner.core.SecureBaseObject;
import us.bringardner.core.util.TlsSockets;

/**
 * The TLS state of one connection: the SSLEngine and its encrypted buffers.
 * Used only by {@link NioConnection} on its reactor thread (netIn also under the input lock).
 * <p>
 * The static methods make engines configured as the blocking framework configures its
 * sockets: TlsSockets for the client settings (host name check, SNI), and ForceTlsVersion.
 *
 * @author Tony Bringardner
 */
final class TlsLayer {

	/**
	 * Makes the SSLEngine for a connection, called when TLS starts.
	 */
	interface EngineFactory {
		SSLEngine create(NioConnection connection) throws java.io.IOException;
	}

	static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

	final SSLEngine engine;
	/** Encrypted bytes from the peer, ready for put() */
	ByteBuffer netIn;
	/** Encrypted bytes to send, position to limit (ready for get()) */
	ByteBuffer netOut;
	volatile boolean handshakeDone;

	TlsLayer(SSLEngine engine) {
		this.engine = engine;
		int packet = engine.getSession().getPacketBufferSize();
		netIn = ByteBuffer.allocate(packet);
		netOut = ByteBuffer.allocate(packet);
		netOut.flip();
	}

	int getApplicationBufferSize() {
		return engine.getSession().getApplicationBufferSize();
	}

	/**
	 * Make room for at least min more bytes in netIn.
	 */
	void growNetIn(int min) {
		if( netIn.remaining() < min ) {
			ByteBuffer tmp = ByteBuffer.allocate(Math.max(netIn.capacity()*2, netIn.position()+min));
			netIn.flip();
			tmp.put(netIn);
			netIn = tmp;
		}
	}

	/**
	 * A server engine: client certificates as configured, ForceTlsVersion applied.
	 */
	static SSLEngine serverEngine(SSLContext ctx, boolean needClientAuth) {
		SSLEngine ret = ctx.createSSLEngine();
		ret.setUseClientMode(false);
		if( needClientAuth ) {
			ret.setNeedClientAuth(true);
		}
		forceVersion(ret);
		return ret;
	}

	/**
	 * A client engine for a host, set up by bjl_core's TlsSockets (SNI unless the host is an 
	 * address, and the host name check when verifyHostname is true), as the blocking 
	 * framework sets up its client sockets.
	 * 
	 * @throws IllegalArgumentException if verifyHostname is true and there is no host name
	 */
	static SSLEngine clientEngine(SSLContext ctx, String host, int port, boolean verifyHostname) {
		SSLEngine ret = TlsSockets.clientEngine(ctx, host, port, verifyHostname);
		forceVersion(ret);
		return ret;
	}

	/**
	 * Some clients don't support TLS 1.3: the ForceTlsVersion system property limits the
	 * engine to one version, as the framework's Connection does for sockets.
	 */
	private static void forceVersion(SSLEngine engine) {
		String force = System.getProperty(SecureBaseObject.PROPERTY_FORCE_TLS_VERSION);
		if( force != null && !force.trim().isEmpty() ) {
			engine.setEnabledProtocols(new String[] {force.trim()});
		}
	}
}
