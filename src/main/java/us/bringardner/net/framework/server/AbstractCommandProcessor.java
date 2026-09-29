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
package us.bringardner.net.framework.server;
/**
 * CommandProcessor reads commands from the input and
 * delegates the processing to a Command.
 * We assume that the input has the form of "command arg1 arg2 ... argn".
 *    
 */
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.TreeMap;

import us.bringardner.net.framework.IConnection;
import us.bringardner.net.framework.IGenericResponseCode;



public abstract  class AbstractCommandProcessor extends AbstractProcessor implements ICommandProcessor {
	
	
	private static final long serialVersionUID = 1L;
	private IRequestContextFactory requestContextFactory = new DefaultRequestContextFactory();	
	private ICommandFactory commandFactory ;
	private boolean debug;
	

	public IRequestContextFactory getRequestContextFactory() {
		return requestContextFactory;
	}

	public void setRequestContextFactory(IRequestContextFactory requestContextFactory) {
		this.requestContextFactory = requestContextFactory;
	}

	public AbstractCommandProcessor () {
		super();
	}

	public AbstractCommandProcessor (ICommandFactory factory) {
		this();
		setCommandFactory(factory);
	}

	public ICommandFactory getCommandFactory() {
		return commandFactory;
	}


	public void setCommandFactory(ICommandFactory commandFactory) {
		this.commandFactory = commandFactory;
	}


	public void run() {
		IConnection con = getConnection();
		running = true;
		Map<String,String> cmdUsed = new TreeMap<String, String>();

		// Greeting is sent here (not on the server accept thread) so a slow client 
		// or TLS handshake only blocks this processor.
		String greeting = takePendingGreeting();
		if( greeting != null ) {
			try {
				reply(greeting);
			} catch (IOException e) {
				logDebug("Can't send greeting, closing connection", e);
				stop();
			}
		}

		while(running &&  !stopping ) {
			String line = null;
			try {
				line = con.readLine();
			} catch(SocketTimeoutException e) {
				//  Idle, keep waiting. Server.doAdmin closes connections that are idle too long.
				continue;
			} catch(IOException e) {
				if( !isClosed(con) && !stopping ) {
					logError("Error reading request",e);
				}
				break;
			}

			if( line == null ) {
				logDebug("read null??? EOF reached? Connection must be closed by client or network stack error.");
				break;
			}

			try {
				processLine(line, cmdUsed);
			} catch(IOException e) {
				//  Can't talk to the client any more
				if( !isClosed(con) && !stopping ) {
					logError("I/O error processing request",e);
				}
				break;
			} catch(RuntimeException e) {
				//  A failed command should not end the session
				logError("Error processing command "+firstToken(line),e);
				try {
					reply(REPLY_500_GENERIC_ERROR, "An error occured processing the request.");
				} catch(IOException ex) {
					break;
				}
			} catch(Error e) {
				logError("Fatal error in processor",e);
				break;
			}
		}

		if( !stopping ) {
			stop();
		}
		running = false;
		getServer().removeClient(this);
		try {
			con.close();
		} catch (IOException e) {
			logError("error on close", e);
		}

		if( isDebug()) {
			System.out.println(""+cmdUsed.size()+" Server commands used");
			for(String key : cmdUsed.keySet()) {
				System.out.println("\t"+key);
			}
		}
	}

	/**
	 * Parse and execute a single request line.
	 * 
	 * @throws IOException if the connection can no longer be used, this ends the session. 
	 * Any RuntimeException is reported to the client as a 500 and the session continues.
	 */
	protected void processLine(String line, Map<String,String> cmdUsed) throws IOException {
		// Only log the command name, the rest of the line may contain credentials (e.g. PASS)
		logDebug("Received command="+firstToken(line));
		IRequestContext context = getRequestContextFactory().getRequestContext(line);

		ICommand command = getCommandFactory().getCommand(context);
		if( command == null ) {
			reply(IGenericResponseCode.REPLY_500_GENERIC_ERROR, "Not a valid command ("+line+").");
		} else {
			if( isDebug()) {
				cmdUsed.put(command.getName(), command.getName());
			}
			if(!command.requiresAuthorization() 
					|| 
					isAuthorized(command.getPermission()) 
					){
				command.execute(this,context);
			} else {
				reply(IGenericResponseCode.REPLY_500_GENERIC_ERROR, command.getName()+" not authorized");
			}
		}
	}

	private static String firstToken(String line) {
		if( line == null ) {
			return null;
		}
		String tmp = line.trim();
		int idx = tmp.indexOf(' ');
		return idx > 0 ? tmp.substring(0, idx) : tmp;
	}

	private static boolean isClosed(IConnection con) {
		java.net.Socket sock = con.getSocket();
		return sock == null || sock.isClosed();
	}

	public void reply(int responseCode, String text) throws IOException {
		reply(translateResponseCode(responseCode)+" "+text);
		
	}

	public void reply(String text) throws IOException {
		IConnection con = getConnection();
		con.writeLine(text);
		con.flush();
		
	}

	public void setDebug(boolean b) {
		this.debug = b;
	}
	
	public boolean isDebug() {
		return debug;
	}
	
	

}
