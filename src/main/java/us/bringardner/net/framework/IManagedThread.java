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

public interface IManagedThread extends Runnable {
	
	/**
	 * @return true is the thread is currently running.
	 */
	public boolean isRunning() ;
	/**
	 * Start this thread.
	 */
	public void start();
	/**
	 * Request the thread stop gracefully.
	 */
	public void stop();
	
	/**
	 * @param The name of this thread
	 */
	public void setName(String name);
	
	/**
	 * @return The name of this thread
	 */
	public String getName();
	
	/**
	 * @return true is this is a Daemon thread.
	 * 
	 * The JVM will continue to run until all 'non daemon' threads 
	 * have stopped or System.exit is called. 
	 * 
	 * @see java.lang.Thread.setDaemon()  
	 *  
	 */
	public boolean isDaemon();

	/**
	 * @param true it this is a Daemon Thread. 
	 * 
	 * The JVM will continue to run until all 'non daemon' threads 
	 * have stopped or System.exit is called. 
	 * 
	 * @see java.lang.Thread.setDaemon()  
	 * 
	 */
	public void setDaemon(boolean trueOrFalse);
}
