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
 * ~version~V000.00.02-V000.00.01-V000.00.00-
 */
package us.bringardner.net.framework.server;


import us.bringardner.core.BaseObject;

public class DefaultRequestContext extends BaseObject implements IRequestContext {
	private static final long serialVersionUID = 1L;
	
	private static  String defaultSeperator = " ";
	
	
	private String commandLine;
	private String [] tokens;
	private String seperator =getDefaultSeperator();
	private int pos=0;
	
	
	public static String getDefaultSeperator() {
		return defaultSeperator;
	}



	public static void setDefaultSeperator(String defaultSeperator) {
		DefaultRequestContext.defaultSeperator = defaultSeperator;
	}



	public DefaultRequestContext(String commandLine){
		super();
		setCommandLine(commandLine);
	}
	
	

	public String getCommandLine() {
		return commandLine;
	}

	public String getFirstToken() {
		getTokens();
		pos = 0;
		
		return getNextToken();
	}

	public String getNextToken() {
		String ret = null;
		String [] tokens = getTokens();
		if( tokens != null && tokens.length > pos) {
			ret = tokens[pos++];
		}

		return ret;
	}

	public String getRemainingTokens() {
		String ret = null;
		
		if( hasNext() ) {
			StringBuffer buf = new StringBuffer();
			while( hasNext() ) {
				if( buf.length() > 0 ) {
					buf.append(seperator);
				}
				buf.append(getNextToken());
			}
			ret = buf.toString();
		}

		
		return ret;
	}

	public String getSeperator() {
		return seperator;
	}

	public String[] getTokens() {
		if( tokens == null ) {
			tokens = getCommandLine().split(getSeperator());
		}
		
		return tokens;
	}

	public boolean hasNext() {
		boolean ret = false;
		String [] tokens = getTokens();
		ret = tokens != null && tokens.length > pos;
		
		return ret;
	}

	public String peekNext() {
		String ret = null;
		if( hasNext()) {
			ret = getTokens()[pos];
		}
		
		return ret;
	}

	public void setSeperator(String seperator) {
		this.seperator = seperator;
	}

	public void setCommandLine(String commandLine) {
		this.commandLine = commandLine;
		
	}

}
