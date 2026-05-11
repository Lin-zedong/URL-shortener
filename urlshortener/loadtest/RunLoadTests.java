package urlshortener.loadtest;

/**
 * Точка запуска нагрузочного тестирования из IntelliJ IDEA.
 * В IDEA достаточно открыть этот класс и нажать Run рядом с методом main.
 */
public final class RunLoadTests {

    private RunLoadTests() {
    }

    public static void main(String[] args) throws Exception {
        String[] effectiveArgs = args.length == 0
                ? new String[]{"--duration=30", "--list-rps=50"}
                : args;
        LoadTestRunner.main(effectiveArgs);
    }
}
