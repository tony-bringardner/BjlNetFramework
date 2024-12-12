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
package us.bringardner.net.framwork.server;

/*
 * Nothing more that on object wrapper to aid in debugging.
 */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

public class SocketProxyX extends Socket {

	Socket socket;
	int port;

	public SocketProxyX(Socket socket) {
		this.socket = socket;		
	}

	public void setSoTimeout(int timeout) throws SocketException {
		socket.setSoTimeout(timeout);		
	}

	public void close() throws IOException {
		socket.close();		
	}

	public InputStream getInputStream() throws IOException {		
		return socket.getInputStream();
	}

	public OutputStream getOutputStream() throws IOException {
		return socket.getOutputStream();
	}

	public InetAddress getInetAddress() {	
		return socket.getInetAddress();
	}

	public boolean isClosed() {
		return socket.isClosed();
	}

	public static void main(String [] args) {
		for(Method method: Socket.class.getDeclaredMethods()) {
			int mods = method.getModifiers();
			Class<?> ret = method.getReturnType();
			Parameter[] parms = method.getParameters();
			StringBuilder buf  =new StringBuilder();
			StringBuilder names  =new StringBuilder();
			for(Parameter p : parms) {
				String name = p.getName();
				Type type = p.getParameterizedType();
				//System.out.println(name+" "+type);
				if( buf.length()>0) {
					buf.append(',');
					names.append(',');
				}
				buf.append(type.getTypeName());
				buf.append(' ');
				buf.append(name);
				names.append(name);
			}

			if( Modifier.isPublic(mods)) {


				System.out.println("\tpublic "+ret+" "+method.getName()+"("+buf+"){");
				if(ret != void.class) {
					System.out.print("\t\treturn ");
				} else {
					System.out.print("\t\t");
				}

				System.out.println("target."+method.getName()+"("+names+");");
				System.out.println("\t}");
			}
		}
	}
}
