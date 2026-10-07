package us.bringardner.net.framework.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import us.bringardner.core.BaseObject;
import us.bringardner.net.framework.server.IPrincipal.State;
import us.bringardner.io.IoUtils;


/**
 * Users are defined one per line:
 * <pre>
 * name , password , perm1|perm2 , key=val|key=val
 * </pre>
 * The password may be plain text (not recommended) or a hash created with
 * {@link #hashPassword(char[])}, e.g. 
 * <pre>
 * java -cp ... us.bringardner.net.framework.server.FileBasedAcl mySecret
 * </pre>
 * which prints a value like {PBKDF2}210000:salt:hash to paste into the password field.
 */
public class FileBasedAcl extends BaseObject implements IAccessControlList {

	public static String PROP_FILE_NAME = "userFile";

	public static final String HASH_PREFIX = "{PBKDF2}";
	private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int HASH_ITERATIONS = 210000;
	private static final int HASH_BITS = 256;
	private static final int SALT_BYTES = 16;

	/*
	 * Each PBKDF2 hash takes a few hundred ms of CPU by design. Unlimited, a burst of login
	 * attempts (wrong passwords or made-up user names cost the same) uses every core and slows
	 * every transfer and session. So only a few run at once; the rest wait their turn (BJL-40).
	 */
	/** System property: how many password hashes may run at once (default: half the cores, at least 1) */
	public static final String PROP_MAX_CONCURRENT_HASHES = "FileBasedAcl.maxConcurrentHashes";
	/** System property: ms a login waits for its turn before it fails (default 30000) */
	public static final String PROP_HASH_WAIT_MS = "FileBasedAcl.hashWaitMs";
	public static final int DEFAULT_HASH_WAIT_MS = 30000;

	private static volatile int maxConcurrentHashes = Math.max(1, Integer.getInteger(PROP_MAX_CONCURRENT_HASHES,
			Runtime.getRuntime().availableProcessors() / 2));
	private static volatile Semaphore hashPermits = new Semaphore(maxConcurrentHashes, true);
	private static volatile long hashWaitMs = Math.max(0, Long.getLong(PROP_HASH_WAIT_MS, DEFAULT_HASH_WAIT_MS));
	private static final AtomicInteger hashesInProgress = new AtomicInteger();

	/** @return how many password hashes may run at once */
	public static int getMaxConcurrentHashes() {
		return maxConcurrentHashes;
	}

	/**
	 * @param max how many password hashes may run at once (at least 1). Hashes already running
	 * or waiting finish under the old limit.
	 */
	public static synchronized void setMaxConcurrentHashes(int max) {
		if( max < 1 ) {
			throw new IllegalArgumentException("max must be at least 1");
		}
		maxConcurrentHashes = max;
		hashPermits = new Semaphore(max, true);
	}

	/** @return ms a password check waits for its turn before it fails */
	public static long getHashWaitMs() {
		return hashWaitMs;
	}

	/** @param ms ms a password check waits for its turn before it fails (the login fails) */
	public static void setHashWaitMs(long ms) {
		if( ms < 0 ) {
			throw new IllegalArgumentException("ms must be >= 0");
		}
		hashWaitMs = ms;
	}

	/** @return password hashes running right now (for monitoring and tests) */
	public static int getHashesInProgress() {
		return hashesInProgress.get();
	}

	/**
	 * @return a salted PBKDF2 hash of the password, suitable for the password field of the user file.
	 */
	public static String hashPassword(char[] password) {
		byte[] salt = new byte[SALT_BYTES];
		new SecureRandom().nextBytes(salt);
		Semaphore permits = hashPermits;
		permits.acquireUninterruptibly();
		byte[] hash;
		try {
			hash = pbkdf2(password, salt, HASH_ITERATIONS, HASH_BITS);
		} finally {
			permits.release();
		}
		Base64.Encoder enc = Base64.getEncoder();
		return HASH_PREFIX+HASH_ITERATIONS+":"+enc.encodeToString(salt)+":"+enc.encodeToString(hash);
	}

	/**
	 * @return true if the password matches a value created by {@link #hashPassword(char[])}.
	 */
	public static boolean verifyPassword(char[] password, String stored) {
		if( password == null || stored == null || !stored.startsWith(HASH_PREFIX)) {
			return false;
		}
		String parts[] = stored.substring(HASH_PREFIX.length()).split(":");
		if( parts.length != 3 ) {
			return false;
		}
		try {
			int iterations = Integer.parseInt(parts[0]);
			byte[] salt = Base64.getDecoder().decode(parts[1]);
			byte[] expected = Base64.getDecoder().decode(parts[2]);
			// Wait for a turn; a login that can't get one in time fails (the client can retry)
			Semaphore permits = hashPermits;
			if( !permits.tryAcquire(hashWaitMs, TimeUnit.MILLISECONDS) ) {
				return false;
			}
			byte[] actual;
			try {
				actual = pbkdf2(password, salt, iterations, expected.length*8);
			} finally {
				permits.release();
			}
			return MessageDigest.isEqual(expected, actual);
		} catch (IllegalArgumentException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Caller holds a permit from hashPermits. */
	private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
		hashesInProgress.incrementAndGet();
		try {
			KeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
			return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).getEncoded();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(HASH_ALGORITHM+" not available", e);
		} finally {
			hashesInProgress.decrementAndGet();
		}
	}

	/**
	 * Print the hash of each argument, for use in a user file.
	 */
	public static void main(String[] args) {
		if( args.length == 0 ) {
			System.err.println("Usage: FileBasedAcl password [password ...]");
			return;
		}
		for (String pw : args) {
			System.out.println(hashPassword(pw.toCharArray()));
		}
	}

	public static class FileBasedPrincipal extends AbstractPrincipal {

		public FileBasedPrincipal(String name) {
			super(name);			
		}

		@Override
		public boolean authenticate(byte[] credentials) {
			byte mine [] = getCredentials();
			if( mine == null || credentials == null ) {
				return false;
			}
			String stored = new String(mine, StandardCharsets.UTF_8);
			if( stored.startsWith(HASH_PREFIX)) {
				return verifyPassword(new String(credentials, StandardCharsets.UTF_8).toCharArray(), stored);
			}
			// Plain text password. Constant time compare so response time doesn't leak how much matched.
			return MessageDigest.isEqual(mine, credentials);
		}
	}

	private Map<String , IPrincipal> users = new HashMap<>();
	private volatile boolean hasHashedPasswords = false;
	private static volatile String dummyHash;

	public FileBasedAcl() {
		super();					
	}

	@Override
	public void initialize(IServer server) throws IOException {
		initialize(server.getName());
	}

	@Override
	public void initialize(String serverName) throws IOException {
		setPropertyPrefix(serverName);

		String path = getProperty(PROP_FILE_NAME);
		if( path == null ) {
			throw new IOException("Can't find a property for "+PROP_FILE_NAME+" ");
		}

		// first try to get file from path
		InputStream in = getClass().getResourceAsStream(path);
		if( in == null ) {
			in = getClass().getResourceAsStream("/"+path);
		}
		if( in == null ) {
			File file = new File(path).getCanonicalFile();
			if(!file.exists() || !file.canRead()) {
				throw new IOException(path+" is not a valid file");
			}
			in = new FileInputStream(file);
		}


		load(in);		
	}

	private void load(InputStream in) throws IOException {
		try {
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			for(String line : text.split("[\n]")) {
				line = line.trim();
				if( !line.isEmpty()&& !line.startsWith("#")) {
					parseLine(line);					
				}
			}
		} finally {
			IoUtils.closeQuietly(in);
		}
	}

	protected void parseLine(String line) {

		String parts[] = line.split("[,]");
		// need at least name and credentials
		if( parts.length < 3) {
			return ;
		}

		// CLean up parts
		for (int idx = 0; idx < parts.length; idx++) {
			parts[idx] = parts[idx].trim();
		}

		FileBasedPrincipal p = new FileBasedPrincipal(parts[0].trim());
		//user1  , password   , one|two|three|four|five, key=val|key=val
		p.setCredentials(parts[1].trim().getBytes(StandardCharsets.UTF_8));
		if( parts[1].trim().startsWith(HASH_PREFIX)) {
			hasHashedPasswords = true;
		}
		users.put(p.getName(),p);
		if( parts.length> 2) {
			if( !parts[2].isEmpty()) {
				String perms [] = parts[2].split("[|]");
				for(String p1 : perms) {
					p1 = p1.trim();
					if( !p1.isEmpty()) {
						p.add(new Permission(p1.trim()));
					}
				}
			}
			if( parts.length> 3) {
				if( !parts[3].isEmpty()) {
					String args [] = parts[3].split("[|]");
					for(String a1 : args) {
						String a2[] = a1.split("[=]");
						if( a2.length == 2) {
							p.setParameter(a2[0].trim(), a2[1].trim());
						}
					}	
				}
			}
		}			
	}

	@Override
	public boolean checkPermission(IPrincipal user, IPermission action) {
		boolean ret = false;
		if( user != null && action != null && user.getState() == State.Authenticated) {
			ret = user.hasPermission(action);
		}		
		return ret;
	}


	@Override
	public void authenticateUnknownUser(byte[] credentials) {
		// Plain text compares take no measurable time, only hashed passwords need matching work.
		if( hasHashedPasswords ) {
			String hash = dummyHash;
			if( hash == null ) {
				hash = dummyHash = hashPassword("not a real password".toCharArray());
			}
			char [] pw = credentials == null ? new char[0] : new String(credentials, StandardCharsets.UTF_8).toCharArray();
			verifyPassword(pw, hash);
		}
	}

	@Override
	public IPrincipal getPrincipal(String user) {
		IPrincipal ret = users.get(user);

		return ret;
	}



}
