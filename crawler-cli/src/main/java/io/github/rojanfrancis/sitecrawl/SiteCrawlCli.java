package io.github.rojanfrancis.sitecrawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point: {@code java -jar sitecrawl.jar --url https://example.com --max-pages 5}.
 * stdout always gets exactly one JSON document, even on failure, so a caller can parse it;
 * stderr gets human-readable progress lines; the exit code says how it went.
 */
public final class SiteCrawlCli {

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_PARTIAL = 2;
    public static final int EXIT_FAILED = 3;
    public static final int EXIT_INVALID = 4;
    public static final int EXIT_BLOCKED = 5;

    private SiteCrawlCli() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(Arrays.asList(args), out, err, new UrlGuard()));
    }

    static int run(List<String> args, PrintStream out, PrintStream err, UrlGuard guard) {
        if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
            err.print(CrawlOptions.USAGE);
            return args.isEmpty() ? EXIT_INVALID : EXIT_SUCCESS;
        }
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            CrawlOptions options = CrawlOptions.parse(args, guard);
            err.println("[sitecrawl] start " + options.startUrl() + " (max " + options.maxPages()
                    + " pages, depth " + options.maxDepth() + ", " + options.maxSeconds() + "s budget)");
            CrawlReport report = new Crawler(guard, err).crawl(options);
            out.println(json.writeValueAsString(report));
            return switch (report.status()) {
                case "success" -> EXIT_SUCCESS;
                case "partial" -> EXIT_PARTIAL;
                case "blocked" -> EXIT_BLOCKED;
                default -> EXIT_FAILED;
            };
        } catch (CrawlOptions.HostNotFoundException e) {
            err.println("[sitecrawl] cannot reach site: " + e.getMessage());
            printError(out, json, "failed", e.getMessage());
            return EXIT_FAILED;
        } catch (CrawlOptions.InvalidOptionsException e) {
            err.println("[sitecrawl] invalid input: " + e.getMessage());
            printError(out, json, "rejected", e.getMessage());
            return EXIT_INVALID;
        } catch (Exception e) {
            err.println("[sitecrawl] unexpected error: " + e);
            printError(out, json, "failed", "Unexpected error: " + e.getClass().getSimpleName());
            return EXIT_ERROR;
        }
    }

    private static void printError(PrintStream out, ObjectMapper json, String status, String message) {
        try {
            out.println(json.writeValueAsString(java.util.Map.of(
                    "tool", "sitecrawl", "version", Crawler.VERSION, "status", status,
                    "error", message, "startedAt", Instant.now().toString())));
        } catch (Exception ignored) {
            out.println("{\"tool\":\"sitecrawl\",\"status\":\"" + status + "\"}");
        }
    }
}
