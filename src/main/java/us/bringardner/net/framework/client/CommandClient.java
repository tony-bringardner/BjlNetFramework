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

	/**
	 * Most commands sent before reading their replies. Bounds what is in flight, so a long
	 * list can't fill both sides' socket buffers and leave client and server each waiting
	 * for the other to read (RFC 2920 section 3.5).
	 */
	public static final int MAX_PIPELINED = 64;

	/**
	 * Pipelining (BJL-43): sends the commands together, with one flush per batch of at most
	 * {@link #MAX_PIPELINED}, then reads one reply per command in order. Each command costs a
	 * round trip with {@link #executeCommand(String)}; a batch costs one. Use it only for
	 * commands the server lets a client send before the previous reply (for SMTP: when EHLO
	 * advertised PIPELINING, and see RFC 2920 for which commands may be grouped).
	 * <p>
	 * If a reply can't be read the exception is thrown and the replies still on the way are
	 * unread, so the connection should be closed.
	 */
	@Override
	public java.util.List<ICommandResponse> executeCommands(java.util.List<String> commands) throws IOException {
		java.util.List<ICommandResponse> ret = new java.util.ArrayList<>(commands.size());
		for (int from = 0; from < commands.size(); from += MAX_PIPELINED) {
			java.util.List<String> batch = commands.subList(from, Math.min(commands.size(), from + MAX_PIPELINED));
			// one write and flush for the batch (Connection.writeLines, BJL-41)
			writeLines(batch);
			for (int idx = 0; idx < batch.size(); idx++) {
				ICommandResponse resp = getCommandResponseFactory().getCommandResponse();
				resp.readResponse(this);
				ret.add(resp);
			}
		}
		return ret;
	}

	public ICommandResponse executeCommand(String command) throws IOException {
		ICommandResponse ret = getCommandResponseFactory().getCommandResponse();
		writeLine(command);
		flush();
		ret.readResponse(this);
		return ret;
	}

	public ICommandResponse executeCommand(String command, String[] args) throws IOException {
		StringBuffer buf = new StringBuffer(command);
		if( args != null ) {
			String seperator = getSeparator();
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
		String sep = getSeparator();
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
