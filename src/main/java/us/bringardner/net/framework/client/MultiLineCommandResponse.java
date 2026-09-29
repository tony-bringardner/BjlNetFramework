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

import us.bringardner.net.framework.IGenericResponseCode;

/**
 * Reads FTP / SMTP style multi-line replies (RFC 959, RFC 5321):
 * <pre>
 * 250-first line
 * 250-second line
 * 250 last line
 * </pre>
 * The code and text come from the first line, every line is in getFullResponse().
 * A reply without the 'nnn-' prefix is handled exactly like SingleCommandResponse.
 * 
 * Use it with CommandClient.setCommandResponseFactory(() -> new MultiLineCommandResponse()).
 */
public class MultiLineCommandResponse extends SingleCommandResponse {

	/** Guards against a server that never sends the final line */
	public static final int DEFAULT_MAX_LINES = 10000;

	private int maxLines = DEFAULT_MAX_LINES;

	public int getMaxLines() {
		return maxLines;
	}

	public void setMaxLines(int maxLines) {
		this.maxLines = maxLines;
	}

	@Override
	public void readResonse(ICommandClient client) throws IOException {
		//  Set this in case some error occurs.
		code = IGenericResponseCode.REPLY_500_GENERIC_ERROR;

		String first = client.readLine();
		if( first == null ) {
			logError("Response is null");
			fullResponse.add("500 Response is null");
			return;
		}
		fullResponse.add(first);
		if( !isContinuation(first)) {
			parseResponseLine(first, client.getSeparator());
			return;
		}

		String prefix = first.substring(0, 3);
		code = Integer.parseInt(prefix);
		text = first.substring(4);
		String end = prefix+" ";

		while( fullResponse.size() < maxLines ) {
			String line = client.readLine();
			if( line == null ) {
				logError("Connection closed in the middle of a multi-line response");
				code = IGenericResponseCode.REPLY_500_GENERIC_ERROR;
				return;
			}
			fullResponse.add(line);
			if( line.startsWith(end) || line.equals(prefix)) {
				return;
			}
		}

		logError("Multi-line response exceeded "+maxLines+" lines");
		code = IGenericResponseCode.REPLY_500_GENERIC_ERROR;
	}

	private static boolean isContinuation(String line) {
		return line.length() >= 4 
				&& Character.isDigit(line.charAt(0)) 
				&& Character.isDigit(line.charAt(1)) 
				&& Character.isDigit(line.charAt(2)) 
				&& line.charAt(3) == '-';
	}
}
