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
 * ~version~
 */

package us.bringardner.net.framework.server;

import java.io.IOException;

/**
 * This class provided authentication and authorization the runtime properties;
 * In the initialize method, it uses the server name as a property prefix and
 * searches for properties in the form of :
 * 'server name'.user0 .. 'server name'.user[n].  The search stops when this name pattern fails to find a value; 
 *   
 * The structure of the property value is a set a fields that are comma ('') separated.
 * The first fields in the user name.  The second field is the user password and the rest of the fields are permission names;
 * 
 * Example: tony,password,permission1,permission2,...
 * 
 */
public class PropertyAuthenticator extends FileBasedAcl  {

	public static final String USER_PROPERTY = "user";
	
	
	
	@Override
	public void initialize(IServer server) throws IOException {		
		String name = server.getName();
		if( name != null && !name.isEmpty()) {
			setPropertyPrefix(name);
		}

		int idx = 0;
		String tmp = getProperty(USER_PROPERTY+idx);
		while( tmp != null ) {
			parseLine(tmp);			
			tmp = getProperty(USER_PROPERTY+(++idx));
		}
		
		logInfo("PropertyAuthenticator Initialezed "+idx+" users");

	}

	
}
