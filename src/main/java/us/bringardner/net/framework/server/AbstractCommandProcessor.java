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
import java.net.SocketException;
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
		
		while(running &&  !stopping ) {
			try {
				String line = con.readLine();
				if( line == null ) {
					logDebug("read null??? EOF reached? Connection must be closed by client or network stack error.");
					stop();
				} else {
					logDebug("Received line="+line);
					IRequestContext context = getRequestContextFactory().getRequestContext(line);
					
					ICommand command = getCommandFactory().getCommand(context);
					if( command == null ) {
						reply(IGenericResponseCode.REPLY_500_GENERIC_ERROR, "Not a valid command ("+line+").");
					} else {
						/*
						 * Once we have a command it's pretty simple
						 * 1>  Make sure the command is authorized.
						 * 2>  Execute the command.
						 */
						if( isDebug()) {
							cmdUsed.put(command.getName(), command.getName());
						}
						if(!command.requiresAuthorization() 
								|| 
								isAuthorized(command.getPermission()) 
								){
							command.execute(this,context);
						} else {
							
							// Authorized 
							reply(IGenericResponseCode.REPLY_500_GENERIC_ERROR, command.getName()+" not authorized");
						}
					}
				}
			} catch(SocketTimeoutException e) {
				//  We'll ignore these.  May not want to do this in all cases.
			} catch (SocketException e) {
				logError("Error in run",e);
				stop();
			} catch (IOException e) {
				logError("Error in run",e);
				stop();
			} catch(Exception e) {
				logError("Error in run",e);
				try {
					reply(REPLY_500_GENERIC_ERROR, "An error occured processing the request. Error ="+e);
				} catch(IOException ex) {
					logError("Error trying to reply to client",ex);
					stop();					
				}
			} catch(Throwable t) {
				logError("Error in run",t);
				try {
					reply(REPLY_500_GENERIC_ERROR, "An error occured processing the request. Error ="+t);
				} catch(IOException ex) {
					logError("Error trying to reply to client",ex);
					stop();					
				}
				
			}
		}
		
		running = false;
		getServer().removeClient(this);
		con.close();
		if( isDebug()) {
			System.out.println(""+cmdUsed.size()+" Server commands used");
			for(String key : cmdUsed.keySet()) {
				System.out.println("\t"+key);
			}
		}
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
