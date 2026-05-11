package urlshortener.loadtest;

/**
 * Удобная точка входа для IDEA: тестирование ёмкости внешне развернутого стека.
 */
public final class RunDistributedCapacityTestsInIdea {

    private RunDistributedCapacityTestsInIdea() {
    }

    public static void main(String[] args) throws Exception {
        LoadTestRunner.main(new String[]{
                "--external=true",
                "--base-url=https://localhost",
                "--duration=30",
                "--capacity=true",
                "--results-dir=loadtest-results"
        });
    }
}
