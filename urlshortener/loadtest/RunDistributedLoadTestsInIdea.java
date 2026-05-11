package urlshortener.loadtest;

/**
 * Удобная точка входа для IDEA: нагрузочное тестирование внешне развернутого стека через HTTPS reverse proxy.
 */
public final class RunDistributedLoadTestsInIdea {

    private RunDistributedLoadTestsInIdea() {
    }

    public static void main(String[] args) throws Exception {
        LoadTestRunner.main(new String[]{
                "--external=true",
                "--base-url=https://localhost",
                "--duration=60",
                "--results-dir=loadtest-results"
        });
    }
}
