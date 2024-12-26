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

import java.io.IOException;

/**
 * Access Control list maintains or produces a list of principles (a.k..a. users) at runtime 
 * and is responsible for authentication and authorization.
 * 
 * initialize 
 * 
 */
public interface IAccessControlList {

	/**
	 * Do any work that needs to be done to initialize this controller
	 * (i.e. load principles from someplace)
	 * 
	 * @param server the sever that will used this controller
	 * @throws IOException
	 */
	void initialize (IServer server) throws IOException;
	
	/**
	 * 
	 * @param user
	 * @param action
	 * @return true if the principal is authorized to execute or access the action.
	 */
	boolean checkPermission(IPrincipal user, IPermission action);
	
	/**
	 * 
	 * @param user
	 * @param password
	 * @return an authenticated principle or null if authentication failed.
	 */
	IPrincipal getPrincipal(String user, byte[] password);

}
