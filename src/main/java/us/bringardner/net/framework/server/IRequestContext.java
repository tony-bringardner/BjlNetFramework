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
 * Request context manages the data passed from
 * The Processor to the Command.
 * 
 */
import java.io.Serializable;

public interface IRequestContext extends Serializable {
	/**
	 * @return the String configured as a token seperator for this IRequestContext.
	 */
	public String getSeperator();
	
	/**
	 * @param seperator
	 */
	public void setSeperator(String seperator);
	
	/**
	 * Set the original command line. 
	 * 
	 * @param commandLine
	 */
	public void setCommandLine(String commandLine);
	
	/**
	 * @return the original command line as received from the client.
	 */
	public String getCommandLine();
	
	/**
	 * @return The first token of the command line (the command name).
	 */
	public String getFirstToken();
	
	/**
	 * @return The next token from teh command line (The first token 
	 * is consumed to determine the command name so the first call
	 * to getNextToken() will actually return the second token of
	 * the original command line.
	 */
	public String getNextToken();
	
	/**
	 * @return all tokens that have not been consumed as yet by a 
	 * getNextToken or null if none are available.
	 */
	public String getRemainingTokens();
	
	/**
	 * @return True if more tokens are available.
	 */
	public boolean hasNext() ;
	
	/**
	 * @return an array or String representing the command line tokenized by 
	 * the the separator (including the command).
	 */
	public String [] getTokens();
	
	
	/**
	 * @return The next token or null. However, The token is not consumed.
	 */
	public String peekNext();
	
}
