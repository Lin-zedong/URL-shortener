package urlshortener.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Загрузчик статических CSS/JS ресурсов из classpath или файловой системы проекта.
 */
public final class StaticAssets {

    private StaticAssets() {
    }

    public static String readText(String resourcePath) {
        String normalized = normalize(resourcePath);

        // Сначала пробуем загрузить из classpath — это стандартный путь для JAR и build-скриптов.
        try (InputStream inputStream = openFromClasspath(normalized)) {
            if (inputStream != null) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось прочитать ресурс classpath: " + normalized, ex);
        }

        // Если ресурса нет в classpath, выполняется резервный поиск в файловой системе.
        // Это позволяет загружать CSS/JS даже если IDEA не пометила resources как Resources Root.
        Path file = findOnFileSystem(normalized);
        if (file != null) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Не удалось прочитать ресурс файловой системы: " + file, ex);
            }
        }

        throw new IllegalArgumentException("Ресурс не найден в classpath или файловой системе: " + normalized);
    }

    private static InputStream openFromClasspath(String normalized) {
        ClassLoader classLoader = StaticAssets.class.getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(normalized);
        if (inputStream != null) {
            return inputStream;
        }
        return classLoader.getResourceAsStream(stripLeadingSlash(normalized));
    }

    private static Path findOnFileSystem(String normalized) {
        Set<Path> candidates = new LinkedHashSet<>();
        String stripped = stripLeadingSlash(normalized);

        // Типовые кандидаты относительно текущего рабочего каталога.
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        addCandidateSet(candidates, userDir, stripped);

        // Пытаемся определить корень проекта по расположению текущего класса.
        for (Path root : detectProjectRoots()) {
            addCandidateSet(candidates, root, stripped);
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void addCandidateSet(Set<Path> candidates, Path root, String stripped) {
        candidates.add(root.resolve(stripped).normalize());
        candidates.add(root.resolve("resources").resolve(stripped).normalize());
        candidates.add(root.resolve("out").resolve("classes").resolve(stripped).normalize());
        candidates.add(root.resolve("target").resolve("classes").resolve(stripped).normalize());
        candidates.add(root.resolve("build").resolve("classes").resolve("java").resolve("main").resolve(stripped).normalize());
        candidates.add(root.resolve("build").resolve("resources").resolve("main").resolve(stripped).normalize());
        candidates.add(root.resolve("src").resolve("main").resolve("resources").resolve(stripped).normalize());
    }

    private static List<Path> detectProjectRoots() {
        List<Path> roots = new ArrayList<>();
        CodeSource codeSource = StaticAssets.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return roots;
        }
        try {
            Path location = Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(location)) {
                // В режиме JAR родитель — обычно out/, а ещё на уровень выше — корень проекта.
                Path jarParent = location.getParent();
                if (jarParent != null) {
                    roots.add(jarParent);
                    if (jarParent.getParent() != null) {
                        roots.add(jarParent.getParent());
                    }
                }
            } else {
                // При запуске из IDEA location обычно указывает на каталог компиляции.
                Path current = location;
                roots.add(current);
                for (int i = 0; i < 6 && current.getParent() != null; i++) {
                    current = current.getParent();
                    roots.add(current);
                }
            }
        } catch (URISyntaxException ignored) {
            // В случае ошибки оставляем список пустым — user.dir останется запасным вариантом.
        }
        return roots;
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Resource path must not be blank");
        }
        return resourcePath.replace('\\', '/');
    }

    private static String stripLeadingSlash(String value) {
        if (value.startsWith("/")) {
            return value.substring(1);
        }
        return value;
    }
}
