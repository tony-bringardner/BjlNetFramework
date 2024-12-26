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
package us.bringardner.net.framework.client;

import java.io.IOException;




public class CommandClient extends Client implements ICommandClient {

	ICommandResponseFactory commandResponseFactory;
	String seperator=" ";
	
	protected static final long serialVersionUID = 1L;

	public CommandClient() {
		super();
	}
	public CommandClient(String host, int port) {
		super(host, port);
	}

	public ICommandResponse executeCommand(String command) throws IOException {
		ICommandResponse ret = getCommandResponseFactory().getCommandResponse();
		writeLine(command);
		flush();
		ret.readResonse(this);
		return ret;
	}

	public ICommandResponse executeCommand(String command, String[] args) throws IOException {
		StringBuffer buf = new StringBuffer(command);
		if( args != null ) {
			String seperator = getSeperator();
			for (int idx = 0; idx < args.length; idx++) {
				buf.append(seperator);
				buf.append(args[idx]);
			}
		}
		return executeCommand(buf.toString());
	}

	public ICommandResponseFactory getCommandResponseFactory() {
		if(commandResponseFactory==null){
			
			commandResponseFactory = new ICommandResponseFactory() {

				public ICommandResponse getCommandResponse() {	
					return new SingleCommandResponse();
				}	
			};
			
		}
		return commandResponseFactory;
	}

	public void setCommandResponseFactory(
			ICommandResponseFactory commandResponseFactory) {
		this.commandResponseFactory = commandResponseFactory;
	}

	public String getSeperator() {
		return seperator;
	}

	public void setSeperator(String seperator) {
		this.seperator = seperator;
	}
	

	public ICommandResponse executeCommand(String ... args) throws IOException {
		String sep = getSeperator();
		StringBuilder buf = new StringBuilder();
		for (int idx = 0; idx < args.length; idx++) {
			if( idx>0) {
				buf.append(sep);
			}
			buf.append(args[idx]);
		}
		return executeCommand(buf.toString());
	}


}
