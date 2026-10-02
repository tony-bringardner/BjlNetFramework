/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
/**
 * 
 */
package us.bringardner.net.framework;

import java.io.IOException;
import java.net.Socket;

import javax.net.SocketFactory;

import us.bringardner.io.ILineReader;
import us.bringardner.io.ILineWriter;

/**
 * Objects that implement this Interface know how to
 * manage the communication with a client on a connection 
 * accepted by a Server.
 * 
 * @author Tony Bringardner
 *
 */
public interface IConnection extends ILineReader, ILineWriter {

	
	
	/**
	 * @param The Socket 
	 * @throws IOException 
	 */
	public void setSocket(Socket socket) throws IOException;
	
	/**
	 * @return The Socket 
	 */
	public Socket getSocket();
	
	
	/**
	 * @param True if the connection should make a recording of the dialog between 
	 * the client and the Processor.
	 */
	public void setDebug(boolean trueOrFalse);
	
	/**
	 * @return The currents status of the Debug flag.
	 */
	public boolean isDebug();
	
	
	
	
	public  void setSocketFactory(SocketFactory factory);
	public  SocketFactory getSocketFactory();
	
	public void setSecure(boolean newSecure);
	public boolean isSecure();
		
	public void setTimeout(int milliSeconds);
	public int getTimeout();

	
	/**
	 * Close this Connection. 
	 * @throws IOException 
	 */
	public void close() throws IOException;

	/**
	 * @param sslOrTsl
	 */
	public void negotiateSecureSocket(String sslOrTsl) throws IOException;

	/**
	 * Write several lines and flush once, so they go out together (one write, one TCP segment
	 * or TLS record when they fit) instead of one per line (BJL-41).
	 * @param lines the lines, without line ends
	 * @throws IOException if the connection fails
	 */
	public default void writeLines(java.util.List<String> lines) throws IOException {
		for (String line : lines) {
			writeLine(line);
		}
		flush();
	}
	
}
