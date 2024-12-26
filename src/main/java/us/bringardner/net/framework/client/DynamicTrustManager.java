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
package us.bringardner.net.framework.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import us.bringardner.core.BaseObject;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator.ManageAs;

public class DynamicTrustManager extends BaseObject implements X509TrustManager {

	private static Map<String,String> trusted = new TreeMap<String, String>();

	static {
		BufferedReader in =null;
		try {
			in = new BufferedReader(new FileReader(getPersistanceFile()));
			String line = in.readLine();
			while( line != null ) {
				if(!line.startsWith("#")) {
					String [] parts = line.split("[~]");
					if(parts.length == 2) {
						trusted.put(parts[0], parts[1]);
					}
				}
				line = in.readLine();
			}

		} catch (Throwable e) {
			// Ignore this
		} finally {
			if( in != null ) {
				try {
					in.close();
				} catch (Exception e2) {
				}
			}
		}
	}

	private static void saveTrusted() throws IOException {
		PrintStream out = null;
		try {
			out = new PrintStream(getPersistanceFile());
		
			for (Map.Entry<String, String> e : trusted.entrySet()) {
				out.println(e.getKey()+"~"+e.getValue());
			}
			
		} finally {
			if( out != null ) {
				try {
					out.close();
				} catch (Exception e) {
				}
			}
		}
	}
	
	private static File getPersistanceFile() {
		File ret = new File(System.getProperty("user.home"),".bjlTructed");
		return ret;
	}

	public static interface CertificateValidator {
		public enum  ManageAs {REJECT, ACCEPT_ONCE,ACCEPT_ALWAYS};
		public ManageAs validate(X509Certificate cert);
	}

	private static CertificateValidator defaultValidator = new CertificateValidator() {

		public ManageAs validate(X509Certificate cert) {
			return ManageAs.REJECT;
		}
	};


	public static CertificateValidator getDefaultValidator() {
		return defaultValidator;
	}

	public static void setDefaultValidator(CertificateValidator defaultValidator) {
		DynamicTrustManager.defaultValidator = defaultValidator;
	}

	private CertificateValidator validator = getDefaultValidator();

	private X509Certificate[] acceptedIssuers;
	private TrustManager[] trustManagers;

	public DynamicTrustManager(TrustManager[] trustManagers,CertificateValidator validator) {
		this(trustManagers);
		this.validator = validator;
	}

	public DynamicTrustManager(TrustManager[] tm) {
		super();
		trustManagers = tm;
		if( trustManagers == null ) {
			TrustManagerFactory tmf;
			try {
				tmf = TrustManagerFactory.getInstance("SunX509", "SunJSSE");
				tmf.init((KeyStore)null);
				trustManagers = tmf.getTrustManagers();
			} catch (Exception e) {
				logError("Can't get instance of trust manager",e);
			}  
		}

		List<X509Certificate> iss = new ArrayList<X509Certificate>();
		if( trustManagers != null ) {
			for (TrustManager tm2 : trustManagers) {
				if (tm2 instanceof X509TrustManager) {
					X509TrustManager x5 = (X509TrustManager) tm2;
					X509Certificate[] tmp = x5.getAcceptedIssuers();
					if( tmp != null ) {
						for (X509Certificate cert : tmp) {
							iss.add(cert);
						}
					}
				}
			}
		}
		acceptedIssuers = iss.toArray(new X509Certificate[iss.size()]);
	}



	public DynamicTrustManager() {
		this(null);
	}


	public void checkTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		if( chain == null || chain.length < 1) {
			//  should never happen
			throw new CertificateException("No certificates to validate");
		}

		boolean ok = false;

		for (TrustManager tm : trustManagers) {
			if (tm instanceof X509TrustManager) {
				X509TrustManager x5 = (X509TrustManager) tm;
				try {
					x5.checkServerTrusted(chain, authType);
					ok = true;
					break;
				} catch (Throwable e) {
					System.out.println("e="+e);
				}
			}
		}

		if( !ok ) {

			String sig = java.util.Base64.getEncoder().encodeToString(chain[0].getSignature()).replaceAll("[\n]","");
			String name = chain[0].getSubjectDN().getName();
			if( trusted.containsKey(sig)) {
				ok = true;
			} else {
				if( validator != null ) {
					ManageAs action = validator.validate(chain[0]);
					switch (action) {
					case ACCEPT_ALWAYS:
						trusted.put(sig,name);
						ok = true;
						try {
							saveTrusted();
						} catch (IOException e) {
							logError("Can't save trusted", e);
						}
						break;
					case ACCEPT_ONCE:
						//  this will be trusted for the program duration but not persisted
						trusted.put(sig,name);
						ok = true;
						break;
					case REJECT:break;
					default:break;
					}
				}
			}
		}

		if( !ok ) {
			throw new CertificateException("Not valid");
		}
	}

	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		throw new CertificateException("This object i snot intended for clinet processing.");		
	}

	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		checkTrusted(chain, authType);
	}

	public X509Certificate[] getAcceptedIssuers() {
		return acceptedIssuers;
	}

	public void setAcceptedIssuers(X509Certificate[] acceptedIssuers) {
		this.acceptedIssuers = acceptedIssuers;
	}



}
