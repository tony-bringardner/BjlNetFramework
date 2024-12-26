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
import java.util.ArrayList;
import java.util.List;

import us.bringardner.core.BaseObject;
import us.bringardner.net.framework.IGenericResponseCode;

public class SingleCommandResponse extends BaseObject implements ICommandResponse {

	private List<String> fullResponse = new ArrayList<String>();
	private int code;
	private String text;
	
	
	public String[] getFullResponse() {
		return (String[])fullResponse.toArray(new String[fullResponse.size()]);
	}

	public int getResponseCode() {
		return code;
	}

	public String getResponseText() {
		return text;
	}

	public boolean isError() {
		return code >= IGenericResponseCode.REPLY_500_GENERIC_ERROR ;
	}

	public boolean isPositive() {
		return code >= IGenericResponseCode.REPLY_200_GENERIC_OK && code < IGenericResponseCode.REPLY_300_GENERIC_TEMPOARY_OK; 
	}

	public boolean isPositiveIntermediate() {
		return code >= IGenericResponseCode.REPLY_300_GENERIC_TEMPOARY_OK && code < IGenericResponseCode.REPLY_400_GENERIC_TEMPOARY_ERROR;
	}

	public boolean isPositivePreliminary() {
		return code >= IGenericResponseCode.REPLY_100_GENERIC_POSITIVE_PRELIMINARY && code < IGenericResponseCode.REPLY_200_GENERIC_OK;
	}

	public boolean isTemporaryError() {
		return code >= IGenericResponseCode.REPLY_400_GENERIC_TEMPOARY_ERROR && code < IGenericResponseCode.REPLY_500_GENERIC_ERROR;
	}

	public void readResonse(ICommandClient client) throws IOException {
		//  Set this in case some error occurs.
		code = IGenericResponseCode.REPLY_500_GENERIC_ERROR;

		String response = client.readLine();
		if( response == null ) {
			logError("Response is null");
			fullResponse.add("500 Response is null");
		} else {
			fullResponse.add(response);
			String sep = client.getSeperator();
			if( sep == null ) {
				logError("sep = null");
			}

			int idx = response.indexOf(sep);
			if( idx > 0 ) {
				text = response.substring(idx+1);
				response = response.substring(0,idx);
			}
			code = translateResponseCode(response);
		}
	}

	public int translateResponseCode(String code) {
		//  just parse the int
		int ret = Integer.parseInt(code);
		return ret;
	}
	
	public String toString() {
		return "Code='"+getResponseCode()+"' text='"+getResponseText()+"'";
	}

	

}
