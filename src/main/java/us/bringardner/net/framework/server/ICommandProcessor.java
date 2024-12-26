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
 * This interface defines a Processor that will process 
 * incoming requests by deligating to an appropriate 'Command'.
 * 
 * This is in support of a dialog modle where the client sends commands
 * to the server and the server reponse with a response code followed by some optional text.
 * 
 */
package us.bringardner.net.framework.server;

import java.io.IOException;

import us.bringardner.net.framework.IGenericResponseCode;
import us.bringardner.net.framework.IProcessor;



/**
 * @author E015887
 *
 */
public interface ICommandProcessor extends IProcessor,IGenericResponseCode {
	
	
	public boolean isDebug();
	public void setDebug(boolean b);
	
	/**
	 * @return a IRequestContextFactory to manage the commandLine data.
	 */
	public IRequestContextFactory getRequestContextFactory();
	
	/**
	 * @param factory
	 */
	public void setRequestContextFactory(IRequestContextFactory factory);
	/**
	 * @return the CommandFactory defined for this Processor
	 */
	public ICommandFactory getCommandFactory() ;
	
	/**
	 * @param commandFactory
	 */
	public void setCommandFactory(ICommandFactory commandFactory);
	
	/**
	 * Translate the response code from a generic standard to a 
	 * protocol specifig code (if required).
	 * 
	 * The translated value is a String to support protocols
	 * that use somthing other than an integer (like POP3).
	 * 
	 * @param generic response code
	 * @return protocol specific response code as a String
	 */
	public String translateResponseCode(int code);
	
	/**
	 * Send a response to the client in the form of "code text".
	 * The response code is translates to a protocol specific string value.
	 * 
	 * @param responseCode
	 * @param text
	 * @throws IOException 
	 */
	public void reply(int responseCode, String text) throws IOException;
	
	/**
	 * Send a response to the client.  
	 * 
	 * @param text
	 * @throws IOException 
	 */
	public void reply(String text) throws IOException;
	
}
