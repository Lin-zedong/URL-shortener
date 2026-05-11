package urlshortener.loadtest;

/**
 * Точка запуска capacity-сценариев из IntelliJ IDEA.
 * Этот запуск дополняет основной набор нагрузочных проверок сценариями из расчёта ресурсов Excel.
 */
public final class RunCapacityTests {

    private RunCapacityTests() {
    }

    public static void main(String[] args) throws Exception {
        String[] effectiveArgs = args.length == 0
                ? new String[]{"--duration=30", "--list-rps=50", "--capacity=true"}
                : args;
        LoadTestRunner.main(effectiveArgs);
    }
}
