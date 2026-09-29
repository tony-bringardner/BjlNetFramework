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


import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import us.bringardner.core.BaseObject;

public class DefaultRequestContext extends BaseObject implements IRequestContext {
	private static final long serialVersionUID = 1L;
	
	private static  String defaultSeperator = " ";
	
	
	private String commandLine;
	private String [] tokens;
	// Offset of each token in the command line, used by getRemainingTokens()
	private int [] starts;
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

	/**
	 * @return the rest of the command line, starting at the next token, exactly as it was received
	 * (spacing and trailing separators are kept), or null if there are no more tokens. 
	 * All tokens are consumed.
	 */
	public String getRemainingTokens() {
		String ret = null;
		
		if( hasNext() ) {
			ret = getCommandLine().substring(starts[pos]);
			pos = tokens.length;
		}

		return ret;
	}

	public String getSeperator() {
		return seperator;
	}

	public String[] getTokens() {
		if( tokens == null ) {
			String sep = getSeperator();
			// A single character is always literal ("|" or "." would otherwise be regex operators).
			// Longer separators are still treated as a regex for compatibility.
			if( sep.length() == 1 ) {
				sep = Pattern.quote(sep);
			}
			tokenize(getCommandLine(), Pattern.compile(sep));
		}
		
		return tokens;
	}

	/*
	 * Same result as String.split(regex) but also records where each token starts.
	 */
	private void tokenize(String line, Pattern pattern) {
		List<String> list = new ArrayList<String>();
		List<Integer> offsets = new ArrayList<Integer>();
		int index = 0;
		Matcher m = pattern.matcher(line);
		while( m.find() ) {
			if( index == 0 && m.start() == 0 && m.start() == m.end()) {
				// no empty leading token for a zero width match at the beginning
				continue;
			}
			list.add(line.substring(index, m.start()));
			offsets.add(index);
			index = m.end();
		}
		list.add(line.substring(index));
		offsets.add(index);

		// Like split(), drop trailing empty tokens (but keep a single empty token for an empty line)
		int size = list.size();
		while( size > 1 && list.get(size-1).isEmpty()) {
			size--;
		}
		if( size == 1 && list.get(0).isEmpty() && index > 0 ) {
			// The line was nothing but separators
			size = 0;
		}

		String [] t = new String[size];
		int [] o = new int[size];
		for (int idx = 0; idx < size; idx++) {
			t[idx] = list.get(idx);
			o[idx] = offsets.get(idx);
		}
		starts = o;
		tokens = t;
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
		reset();
	}

	public void setCommandLine(String commandLine) {
		this.commandLine = commandLine;
		reset();
	}

	// Tokens were cached, so changing the line or separator previously had no effect once parsed.
	private void reset() {
		tokens = null;
		starts = null;
		pos = 0;
	}

}
