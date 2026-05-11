package urlshortener.loadtest;

/**
 * Совместимая точка запуска нагрузочного тестирования из IntelliJ IDEA.
 */
public final class RunLoadTestsInIdea {

    private RunLoadTestsInIdea() {
    }

    public static void main(String[] args) throws Exception {
        RunLoadTests.main(args);
    }
}
