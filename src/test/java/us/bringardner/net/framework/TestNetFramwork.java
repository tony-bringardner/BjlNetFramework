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
package us.bringardner.net.framework;

import java.io.IOException;
import java.net.Socket;

import javax.net.ssl.SSLContext;

import org.junit.Test;

import junit.framework.TestCase;
import us.bringardner.net.framework.client.CommandClient;
import us.bringardner.net.framework.client.ICommandResponse;
import us.bringardner.net.framework.server.AbstractCommandProcessor;
import us.bringardner.net.framework.server.ICommand;
import us.bringardner.net.framework.server.ICommandFactory;
import us.bringardner.net.framework.server.ICommandProcessor;
import us.bringardner.net.framework.server.IPermission;
import us.bringardner.net.framework.server.IRequestContext;
import us.bringardner.net.framework.server.Server;

public class TestNetFramwork {

	public static final String ECHO_COMMAND = "Echo";

	@Test
	public void testEchoServer() throws IOException {

		int port = 8888;

		/**
		 * Create a simple server that will echo all commands back to the client.
		 */
		Server svr = new Server(port,"EchoServer");
			
		svr.setConnectionFactory(new IConnectionFactory() {

			public IConnection getConnection(Socket socket) throws IOException {

				return new Connection(socket,true) {
					@Override
					public SSLContext getSSLContext(String sslOrTsl)throws IOException {
						return null;
					}
				};
			}
		});

		svr.setProcessorFactory(new IProcessorFactory() {

			
			public IProcessor getProcessor() {

				return new AbstractCommandProcessor() {
					private static final long serialVersionUID = 1L;

					public String translateResponseCode(int code) {
						return ""+code;
					}

					@Override
					public boolean isAuthorized(IPermission action) {
						// everyone is authorized
						return true;
					}
					@Override
					public ICommandFactory getCommandFactory() {
						return new ICommandFactory() {

							private static final long serialVersionUID = 1L;

							public ICommand getCommand(IRequestContext context) {
								ICommand ret = null;

								String cmd = context.getNextToken();
								if( ECHO_COMMAND.equals(cmd)) {
									return new ICommand() {

										private static final long serialVersionUID = 1L;

										public IPermission getPermission() {
											return null;
										}

										public String getName() {
											return ECHO_COMMAND;
										}

										public void execute(ICommandProcessor processor, IRequestContext context)
												throws IOException {
											//  Get the values and return them
											String text = context.getRemainingTokens();
											processor.reply(REPLY_200_GENERIC_OK, text);										
										}
									};
								}
								return ret;
							}
						};
					}
				};
			}
		});

		svr.start();
		int cnt = 0;
		// wait for the server to start
		while( cnt < 5 && !svr.isRunning()) {
			try {
				cnt++;
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
		TestCase.assertTrue(svr.isRunning());

		String msgs [] = {
				"text1",
				"test2"
		};

		/**
		 * Create s simple client that will send commands to the server and 
		 * validate the response.
		 */
		try(CommandClient client = new CommandClient("localhost",port)){
			TestCase.assertTrue("Can't connect to echo server",client.connect());
			for (int idx = 0; idx < msgs.length; idx++) {

				ICommandResponse resp = client.executeCommand(ECHO_COMMAND,msgs[idx]);
				if( !resp.isPositive()) {
					System.out.println(resp);
				}
				// positive response
				
				TestCase.assertTrue("Did not get a positive response",resp.isPositive());
				TestCase.assertEquals(msgs[idx],resp.getResponseText());

			}
		}
		svr.stop();

	}


}
