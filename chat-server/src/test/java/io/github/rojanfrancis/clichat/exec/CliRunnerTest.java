package io.github.rojanfrancis.clichat.exec;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses small shell programs as stand-ins for the CLI to test supervision, not crawling. */
class CliRunnerTest {

    private final CliRunner runner = new CliRunner();

    private ExecutionResult run(String script, Duration timeout, CliRunner.Handle handle, List<String> streamed) throws Exception {
        return runner.run(List.of("/bin/sh", "-c", script), "test", timeout, streamed::add, handle);
    }

    @Test
    void capturesStdoutStderrAndExitCodeSeparately() throws Exception {
        List<String> streamed = new CopyOnWriteArrayList<>();
        ExecutionResult r = run("echo '{\"ok\":true}'; echo progress-1 >&2; echo progress-2 >&2; exit 2",
                Duration.ofSeconds(10), new CliRunner.Handle(), streamed);

        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.exitMeaning()).isEqualTo("partial result");
        assertThat(r.stdout().trim()).isEqualTo("{\"ok\":true}");
        assertThat(r.stderr()).containsExactly("progress-1", "progress-2");
        assertThat(streamed).containsExactly("progress-1", "progress-2");
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    void killsAProcessThatRunsPastTheTimeout() throws Exception {
        long start = System.currentTimeMillis();
        ExecutionResult r = run("sleep 30", Duration.ofMillis(500), new CliRunner.Handle(), new CopyOnWriteArrayList<>());

        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(System.currentTimeMillis() - start).isLessThan(8000);
    }

    @Test
    void cancelStopsTheProcess() throws Exception {
        CliRunner.Handle handle = new CliRunner.Handle();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // test thread
            }
            handle.cancel();
        });
        ExecutionResult r = run("sleep 30", Duration.ofSeconds(20), handle, new CopyOnWriteArrayList<>());

        assertThat(r.cancelled()).isTrue();
        assertThat(r.timedOut()).isFalse();
        assertThat(r.exitMeaning()).isEqualTo("cancelled by user");
    }

    @Test
    void childProcessDoesNotInheritSecrets() throws Exception {
        ExecutionResult r = run("env", Duration.ofSeconds(10), new CliRunner.Handle(), new CopyOnWriteArrayList<>());

        List<String> names = r.stdout().lines().map(l -> l.split("=", 2)[0]).toList();
        assertThat(names).doesNotContain("GEMINI_API_KEY", "HOME", "USER");
        assertThat(names).contains("PATH");
    }

    @Test
    void displayHidesJavaPathsAndQuotesOddArguments() {
        String shown = CrawlerCommand.display(List.of("/usr/bin/java", "-Xmx256m", "-jar", "/opt/sitecrawl.jar",
                "--url", "https://example.com/a b", "--max-pages", "3"));

        assertThat(shown).isEqualTo("sitecrawl --url 'https://example.com/a b' --max-pages 3");
    }
}
