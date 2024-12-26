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
package us.bringardner.net.framework;
/**
 * A minimal set of generic response codes that follow 
 * the command design pattern of many internet protocols.
 * One good example is the FTP protocol (RFC959).
 * 
 * This is a minimal set required for some basic processing.  
 * A protocols that use a different scheme must
 * provide a translation.  
 *  
 * 
 * 
 */
import java.io.Serializable;

public interface IGenericResponseCode extends Serializable {
	/*
	 * 1yz   Positive Preliminary reply

               The requested action is being initiated; expect another
               reply before proceeding with a new command.  (The
               user-process sending another command before the
               completion reply would be in violation of protocol; but
               server-FTP processes should queue any commands that
               arrive while a preceding command is in progress.)  This
               type of reply can be used to indicate that the command
               was accepted and the user-process may now pay attention
               to the data connections, for implementations where
               simultaneous monitoring is difficult.  The server-FTP
               process may send at most, one 1yz reply per command.

	 */
	public static final int REPLY_100_GENERIC_POSITIVE_PRELIMINARY = 100;
	/*
	 * 2yz   Positive Completion reply

               The requested action has been successfully completed.  A
               new request may be initiated.
	 */
	public static final int REPLY_200_GENERIC_OK = 200;
	/*
	 * 3yz   Positive Intermediate reply

               The command has been accepted, but the requested action
               is being held in abeyance, pending receipt of further
               information.  The user should send another command
               specifying this information.  This reply is used in
               command sequence groups.
	 */
	public static final int REPLY_300_GENERIC_TEMPOARY_OK = 300;
	/*
	 *  4yz   Transient Negative Completion reply

               The command was not accepted and the requested action did
               not take place, but the error condition is temporary and
               the action may be requested again.  The user should
               return to the beginning of the command sequence, if any.
               It is difficult to assign a meaning to "transient",
               particularly when two distinct sites (Server- and
               User-processes) have to agree on the interpretation.
               Each reply in the 4yz category might have a slightly
               different time value, but the intent is that the
               user-process is encouraged to try again.  A rule of thumb
               in determining if a reply fits into the 4yz or the 5yz
               (Permanent Negative) category is that replies are 4yz if
               the commands can be repeated without any change in
               command form or in properties of the User or Server
               (e.g., the command is spelled the same with the same
               arguments used; the user does not change his file access
               or user name; the server does not put up a new
               implementation.)

	 */
	public static final int REPLY_400_GENERIC_TEMPOARY_ERROR = 400;
	/*
	 * 5yz   Permanent Negative Completion reply

               The command was not accepted and the requested action did
               not take place.  The User-process is discouraged from
               repeating the exact request (in the same sequence).  Even
               some "permanent" error conditions can be corrected, so
               the human user may want to direct his User-process to
               reinitiate the command sequence by direct action at some
               point in the future (e.g., after the spelling has been
               changed, or the user has altered his directory status.)
	 */
	public static final int REPLY_500_GENERIC_ERROR = 500;
	

}
