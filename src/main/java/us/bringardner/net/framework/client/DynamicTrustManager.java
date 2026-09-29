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
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import us.bringardner.core.BaseObject;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator.ManageAs;

/**
 * Server certificate trust for clients.
 * 
 * A certificate is trusted if:
 * <ol>
 * <li>the JVM trust store trusts it (including the host name check when the socket asks for one), or</li>
 * <li>the user previously approved this certificate <b>for this host</b>, or</li>
 * <li>the {@link CertificateValidator} approves it now.</li>
 * </ol>
 * 
 * Approvals are stored as "host|sha256-fingerprint~subject" in ~/.bjlTructed. 
 * Entries written by earlier versions (keyed by the certificate signature only) are 
 * still honored for any host so users are not asked again, new approvals are always host specific.
 * 
 * This class extends X509ExtendedTrustManager so the host is known during the handshake. 
 * Because of that the JVM does not add its own host name check, that is done 
 * by the delegated JVM trust manager (step 1) or implied by the host specific approval (step 2).
 */
public class DynamicTrustManager extends X509ExtendedTrustManager {

	private static final BaseObject log = new BaseObject();
	private static final String ANY_HOST = "*";

	// Persisted approvals. Sorted (like the TreeMap it replaced) and thread safe.
	private static final Map<String,String> trusted = new ConcurrentSkipListMap<String, String>();
	// ACCEPT_ONCE approvals, kept for the life of the JVM but never written to disk.
	private static final Map<String,String> sessionTrusted = new ConcurrentHashMap<String, String>();

	static {
		try(BufferedReader in = new BufferedReader(new FileReader(getPersistanceFile()))) {
			String line = in.readLine();
			while( line != null ) {
				if(!line.startsWith("#")) {
					String [] parts = line.split("~",2);
					if(parts.length == 2) {
						trusted.put(parts[0], parts[1]);
					}
				}
				line = in.readLine();
			}
		} catch (Throwable e) {
			// Ignore this, no file yet
		}
	}

	private static synchronized void saveTrusted() throws IOException {
		// Write a temp file and move it into place so a crash can't leave a truncated file.
		File target = getPersistanceFile();
		File tmp = new File(target.getParentFile(), target.getName()+".tmp");
		try(PrintStream out = new PrintStream(tmp)) {
			for (Map.Entry<String, String> e : trusted.entrySet()) {
				out.println(e.getKey()+"~"+e.getValue());
			}
			if( out.checkError()) {
				throw new IOException("Error writing "+tmp);
			}
		}
		try {
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static File getPersistanceFile() {
		File ret = new File(System.getProperty("user.home"),".bjlTructed");
		return ret;
	}

	/**
	 * Remove every approval (persisted and ACCEPT_ONCE) for a host.
	 * @return the number of approvals removed
	 */
	public static int removeTrusted(String host) throws IOException {
		String prefix = hostKey(host)+"|";
		int ret = 0;
		for (String key : new ArrayList<String>(trusted.keySet())) {
			if( key.startsWith(prefix) && trusted.remove(key) != null) {
				ret++;
			}
		}
		for (String key : new ArrayList<String>(sessionTrusted.keySet())) {
			if( key.startsWith(prefix) && sessionTrusted.remove(key) != null) {
				ret++;
			}
		}
		if( ret > 0 ) {
			saveTrusted();
		}
		return ret;
	}

	public static interface CertificateValidator {
		public enum  ManageAs {REJECT, ACCEPT_ONCE,ACCEPT_ALWAYS};
		public ManageAs validate(X509Certificate cert);

		/**
		 * Called with the host being connected to (may be null if unknown). 
		 * Override to show the host to the user, the default ignores it.
		 */
		public default ManageAs validate(X509Certificate cert, String host) {
			return validate(cert);
		}
	}

	private static volatile CertificateValidator defaultValidator = new CertificateValidator() {

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
				// PKIX on current JVMs, SunX509 skips some certificate checks
				tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init((KeyStore)null);
				trustManagers = tmf.getTrustManagers();
			} catch (Exception e) {
				log.logError("Can't get instance of trust manager",e);
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

	/**
	 * Check without knowing the host. Only approvals made without a host (or legacy approvals) apply.
	 */
	public void checkTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		checkTrusted(chain, authType, null, null, null);
	}

	private void checkTrusted(X509Certificate[] chain, String authType, String host, Socket socket, SSLEngine engine) throws CertificateException {
		if( chain == null || chain.length < 1) {
			//  should never happen
			throw new CertificateException("No certificates to validate");
		}

		// 1> The JVM trust store
		CertificateException lastError = null;
		TrustManager[] managers = trustManagers == null ? new TrustManager[0] : trustManagers;
		for (TrustManager tm : managers) {
			try {
				if( tm instanceof X509ExtendedTrustManager && socket != null ) {
					((X509ExtendedTrustManager) tm).checkServerTrusted(chain, authType, socket);
				} else if( tm instanceof X509ExtendedTrustManager && engine != null ) {
					((X509ExtendedTrustManager) tm).checkServerTrusted(chain, authType, engine);
				} else if (tm instanceof X509TrustManager) {
					((X509TrustManager) tm).checkServerTrusted(chain, authType);
				} else {
					continue;
				}
				return;
			} catch (CertificateException e) {
				lastError = e;
				log.logDebug("Not trusted by "+tm+": "+e);
			}
		}

		// 2> Previously approved for this host
		X509Certificate cert = chain[0];
		String key = hostKey(host)+"|"+fingerprint(cert);
		if( trusted.containsKey(key) || sessionTrusted.containsKey(key)) {
			return;
		}
		String legacy = legacyKey(cert);
		if( trusted.containsKey(legacy)) {
			log.logDebug("Trusting "+cert.getSubjectX500Principal()+" from a legacy (not host specific) approval");
			return;
		}

		// 3> Ask
		String name = cert.getSubjectX500Principal().getName();
		CertificateValidator v = validator;
		if( v != null ) {
			ManageAs action = v.validate(cert, host);
			if( action == ManageAs.ACCEPT_ALWAYS ) {
				trusted.put(key,name);
				try {
					saveTrusted();
				} catch (IOException e) {
					log.logError("Can't save trusted", e);
				}
				return;
			} else if( action == ManageAs.ACCEPT_ONCE ) {
				//  Trusted for the program duration but not persisted
				sessionTrusted.put(key,name);
				return;
			}
		}

		CertificateException ex = new CertificateException("Certificate for "+(host == null ? "server" : host)+" ("+name+") is not trusted");
		if( lastError != null ) {
			ex.initCause(lastError);
		}
		throw ex;
	}

	private static String hostKey(String host) {
		return host == null || host.isEmpty() ? ANY_HOST : host.toLowerCase(Locale.ROOT);
	}

	private static String fingerprint(X509Certificate cert) throws CertificateException {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
			StringBuilder ret = new StringBuilder(digest.length*2);
			for (byte b : digest) {
				ret.append(String.format("%02x", b & 0xff));
			}
			return ret.toString();
		} catch (NoSuchAlgorithmException | CertificateEncodingException e) {
			throw new CertificateException(e);
		}
	}

	// Key used by earlier versions
	private static String legacyKey(X509Certificate cert) {
		return java.util.Base64.getEncoder().encodeToString(cert.getSignature()).replaceAll("[\n]","");
	}

	private static String peerHost(Socket socket) {
		if (socket instanceof SSLSocket) {
			SSLSession session = ((SSLSocket) socket).getHandshakeSession();
			if( session != null ) {
				return session.getPeerHost();
			}
		}
		return null;
	}

	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		throw new CertificateException("This object is not intended for client certificate processing.");		
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		checkClientTrusted(chain, authType);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		checkClientTrusted(chain, authType);
	}

	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		checkTrusted(chain, authType);
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		checkTrusted(chain, authType, peerHost(socket), socket, null);
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		checkTrusted(chain, authType, engine == null ? null : engine.getPeerHost(), null, engine);
	}

	public X509Certificate[] getAcceptedIssuers() {
		return acceptedIssuers;
	}

	public void setAcceptedIssuers(X509Certificate[] acceptedIssuers) {
		this.acceptedIssuers = acceptedIssuers;
	}

}
