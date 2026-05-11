package urlshortener.service;

import urlshortener.model.ServiceResult;
import urlshortener.model.User;
import urlshortener.store.DataStore;
import urlshortener.util.PasswordHasher;
import urlshortener.util.ValidationUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис пользователей: регистрация, аутентификация и поиск владельца по идентификатору.
 */
public final class UserService {

    private final DataStore dataStore;
    private final PasswordHasher passwordHasher;

    public UserService(DataStore dataStore, PasswordHasher passwordHasher) {
        this.dataStore = dataStore;
        this.passwordHasher = passwordHasher;
    }

    /**
     * Создаёт пользователя после нормализации имени и проверки пароля.
     */
    public ServiceResult<User> register(String username, String displayName, String password, String confirmPassword) {
        try {
            String normalizedUsername = ValidationUtils.normalizeUsername(username);
            String normalizedDisplayName = normalizeDisplayName(displayName);
            ValidationUtils.validatePassword(password, confirmPassword);

            if (dataStore.findUserByUsername(normalizedUsername).isPresent()) {
                return ServiceResult.failure("Username already exists");
            }

            PasswordHasher.SaltedHash saltedHash = passwordHasher.hash(password);
            User user = new User(
                    UUID.randomUUID(),
                    normalizedUsername,
                    normalizedDisplayName,
                    saltedHash.hash(),
                    saltedHash.salt(),
                    Instant.now()
            );
            dataStore.saveUser(user);
            return ServiceResult.success("Registration successful", user);
        } catch (IllegalArgumentException ex) {
            return ServiceResult.failure(ex.getMessage());
        }
    }

    /**
     * Проверяет пароль и возвращает владельца ссылок.
     */
    public ServiceResult<User> authenticate(String username, String password) {
        try {
            String normalizedUsername = ValidationUtils.normalizeUsername(username);
            Optional<User> optionalUser = dataStore.findUserByUsername(normalizedUsername);
            if (optionalUser.isEmpty()) {
                return ServiceResult.failure("Invalid username or password");
            }
            User user = optionalUser.get();
            if (!passwordHasher.verify(password, user.passwordHash(), user.passwordSalt())) {
                return ServiceResult.failure("Invalid username or password");
            }
            return ServiceResult.success("Login successful", user);
        } catch (IllegalArgumentException ex) {
            return ServiceResult.failure(ex.getMessage());
        }
    }

    public Optional<User> findById(UUID userId) {
        return dataStore.findUserById(userId);
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be empty");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("Display name must not exceed 64 chars");
        }
        return trimmed;
    }
}
