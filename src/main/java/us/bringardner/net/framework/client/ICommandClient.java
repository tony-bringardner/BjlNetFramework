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




public interface ICommandClient extends IClient {
	
	/** @deprecated misspelled, call {@link #setSeparator(String)} */
	@Deprecated
	public void setSeperator(String seperator);
	/** @deprecated misspelled, call {@link #getSeparator()} */
	@Deprecated
	public String getSeperator();

	/** @return the separator placed between a command and its arguments */
	public default String getSeparator() {
		return getSeperator();
	}

	public default void setSeparator(String separator) {
		setSeperator(separator);
	}
	
	public ICommandResponse executeCommand(String command) throws IOException;

	/**
	 * Run several commands and return their replies in order. {@link CommandClient} sends
	 * them together (pipelining, e.g. SMTP PIPELINING, RFC 2920); this default runs them one
	 * at a time. Only pipeline commands the server allows to be sent before the previous
	 * reply arrives.
	 * @param commands the command lines
	 * @return one reply per command, in order
	 * @throws IOException if the connection fails
	 */
	public default java.util.List<ICommandResponse> executeCommands(java.util.List<String> commands) throws IOException {
		java.util.List<ICommandResponse> ret = new java.util.ArrayList<>(commands.size());
		for (String command : commands) {
			ret.add(executeCommand(command));
		}
		return ret;
	}
	public ICommandResponseFactory getCommandResponseFactory();
	public void setCommandResponseFactory(ICommandResponseFactory commandResponseFactory);
	public ICommandResponse executeCommand(String ... args) throws IOException;
	
}
