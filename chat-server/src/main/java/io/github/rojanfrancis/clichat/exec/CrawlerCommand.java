package io.github.rojanfrancis.clichat.exec;

import io.github.rojanfrancis.clichat.intent.TaskIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the argument list for the one approved tool, sitecrawl. Each value is a separate
 * argv entry handed straight to the OS (no shell), so nothing in a URL can be read as a
 * command, pipe or redirect.
 */
@Component
public class CrawlerCommand {

    static final int PER_REQUEST_TIMEOUT_SECONDS = 10;
    static final int CRAWL_BUDGET_SECONDS = 45;

    private final Path jar;
    private final String javaBinary;

    public CrawlerCommand(@Value("${crawler.jar}") String jar) {
        this.jar = Path.of(jar).toAbsolutePath().normalize();
        this.javaBinary = ProcessHandle.current().info().command().orElse("java");
    }

    public boolean isAvailable() {
        return Files.isRegularFile(jar);
    }

    public Path jar() {
        return jar;
    }

    public List<String> build(TaskIntent intent) {
        if (!intent.runsCli()) {
            throw new IllegalArgumentException("Intent does not run the CLI: " + intent.action());
        }
        return List.of(
                javaBinary, "-Xmx128m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", jar.toString(),
                "--url", intent.url(),
                "--max-pages", Integer.toString(intent.maxPages()),
                "--max-depth", Integer.toString(intent.maxDepth()),
                "--timeout", Integer.toString(PER_REQUEST_TIMEOUT_SECONDS),
                "--max-seconds", Integer.toString(CRAWL_BUDGET_SECONDS));
    }

    /** The command as shown in the chat: "sitecrawl" instead of the full java and jar paths. */
    public static String display(List<String> argv) {
        int jarIndex = argv.indexOf("-jar");
        List<String> args = jarIndex >= 0 ? argv.subList(jarIndex + 2, argv.size()) : argv;
        StringBuilder sb = new StringBuilder("sitecrawl");
        for (String a : args) {
            sb.append(' ').append(a.matches("[\\w./:=?&%+-]+") ? a : "'" + a.replace("'", "'\\''") + "'");
        }
        return sb.toString();
    }
}
