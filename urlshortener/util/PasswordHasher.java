package urlshortener.util;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-хеширование паролей с солью для хранения без открытого пароля.
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Генерирует соль и PBKDF2-хеш для нового пароля.
     */
    public SaltedHash hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);
        String encodedHash = encode(rawPassword, salt);
        return new SaltedHash(encodedHash, encodedSalt);
    }

    /**
     * Сравнивает рассчитанный хеш с сохранённым значением.
     */
    public boolean verify(String rawPassword, String expectedHash, String encodedSalt) {
        byte[] salt = Base64.getDecoder().decode(encodedSalt);
        String actual = encode(rawPassword, salt);
        return expectedHash != null && expectedHash.equals(actual);
    }

    private String encode(String rawPassword, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hashBytes = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Не удалось выполнить хеширование пароля", e);
        }
    }

    public record SaltedHash(String hash, String salt) {
    }
}
