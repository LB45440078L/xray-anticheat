package io.xrayac.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing, token generation and constant-time comparison.
 *
 * <h2>Password storage</h2>
 * PBKDF2-HMAC-SHA256 with a per-password random salt. The stored form is self-describing:
 *
 * <pre>pbkdf2-sha256$&lt;iterations&gt;$&lt;salt-base64&gt;$&lt;hash-base64&gt;</pre>
 *
 * The algorithm and iteration count travel with the hash, so the cost can be raised later without
 * invalidating existing credentials - verification reads the parameters from the stored string
 * rather than assuming today's constants.
 *
 * <p>This is a deliberate choice over a plain digest. The panel is reachable by anything that can
 * reach the port, so a leaked configuration file must not yield a password that can be cracked by
 * comparing against a table of SHA-256 digests.
 */
public final class Credentials {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";

    /**
     * Iteration count. Chosen so that a single verification costs on the order of a hundred
     * milliseconds - imperceptible at login, and the reason an offline attacker cannot test
     * candidates quickly against the stored hash.
     */
    private static final int DEFAULT_ITERATIONS = 210_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Credentials() {
    }

    /** Hashes a password into a storable, self-describing string. */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, DEFAULT_ITERATIONS);
        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + DEFAULT_ITERATIONS + "$"
                + encoder.encodeToString(salt) + "$" + encoder.encodeToString(key);
    }

    /**
     * Verifies a password against a stored hash.
     *
     * @return true only if the password matches; false for a malformed hash as well as a wrong
     *         password, so a corrupt entry denies access rather than throwing
     */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = derive(password, salt, iterations);
        // Constant time: the comparison must not leak how many leading bytes matched.
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // PBKDF2WithHmacSHA256 is required by the Java SE specification, so this cannot happen
            // on a conforming JVM. Rethrowing as an error is better than logging and continuing
            // with a weaker path that would silently accept a password.
            throw new IllegalStateException("PBKDF2 unavailable on this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * A fresh random token, URL-safe and unpadded.
     *
     * <p>Used for session ids and CSRF tokens. {@link SecureRandom} rather than
     * {@link java.util.Random}, because a guessable session id is a session hijack.
     */
    public static String newToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /**
     * A random initial password for the first start, in a form a human can retype from the console.
     *
     * <p>Ambiguous characters are left out: the operator has to copy this by hand out of a log file,
     * and {@code 0/O} and {@code 1/l/I} are a support ticket waiting to happen. 20 characters from a
     * 32-character alphabet is still ~100 bits of entropy.
     */
    public static String newInitialPassword() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder out = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /** Constant-time string comparison, for tokens that are compared but not hashed. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
