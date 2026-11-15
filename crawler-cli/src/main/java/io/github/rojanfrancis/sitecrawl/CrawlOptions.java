package io.github.rojanfrancis.sitecrawl;

import java.net.URI;
import java.util.List;

/**
 * Validated command-line options. Every number is range-checked here, so the crawler
 * never runs with limits the caller did not intend.
 */
public record CrawlOptions(URI startUrl, int maxPages, int maxDepth, int timeoutSeconds, int maxSeconds) {

    public static final int MAX_PAGES_LIMIT = 25;
    public static final int MAX_DEPTH_LIMIT = 3;
    public static final int TIMEOUT_LIMIT = 30;
    public static final int MAX_SECONDS_LIMIT = 120;

    public static final String USAGE = """
            Usage: java -jar sitecrawl.jar --url <http(s) url> [options]

              --url <url>          Page to start from (required, http or https only)
              --max-pages <n>      Pages to fetch, 1-%d (default 5)
              --max-depth <n>      Link depth from the start page, 0-%d (default 1)
              --timeout <s>        Timeout per request in seconds, 1-%d (default 10)
              --max-seconds <s>    Total time budget in seconds, 5-%d (default 45)
              --help               Show this help

            Writes one JSON report to stdout and progress logs to stderr.
            Exit codes: 0 success, 2 partial, 3 failed, 4 invalid input, 5 blocked by robots.txt
            """.formatted(MAX_PAGES_LIMIT, MAX_DEPTH_LIMIT, TIMEOUT_LIMIT, MAX_SECONDS_LIMIT);

    /** Raised for anything the caller got wrong; maps to exit code 4. */
    public static class InvalidOptionsException extends RuntimeException {
        public InvalidOptionsException(String message) {
            super(message);
        }
    }

    /** The URL is fine but its host does not resolve; maps to exit code 3 (failed). */
    public static class HostNotFoundException extends InvalidOptionsException {
        public HostNotFoundException(String host) {
            super("Host not found: " + host);
        }
    }

    public static CrawlOptions parse(List<String> args, UrlGuard guard) {
        String url = null;
        int maxPages = 5, maxDepth = 1, timeout = 10, maxSeconds = 45;

        for (int i = 0; i < args.size(); i++) {
            String flag = args.get(i);
            switch (flag) {
                case "--url" -> url = value(args, ++i, flag);
                case "--max-pages" -> maxPages = intValue(args, ++i, flag, 1, MAX_PAGES_LIMIT);
                case "--max-depth" -> maxDepth = intValue(args, ++i, flag, 0, MAX_DEPTH_LIMIT);
                case "--timeout" -> timeout = intValue(args, ++i, flag, 1, TIMEOUT_LIMIT);
                case "--max-seconds" -> maxSeconds = intValue(args, ++i, flag, 5, MAX_SECONDS_LIMIT);
                default -> throw new InvalidOptionsException("Unknown option: " + flag);
            }
        }
        if (url == null) {
            throw new InvalidOptionsException("--url is required");
        }
        return new CrawlOptions(guard.validate(url), maxPages, maxDepth, timeout, maxSeconds);
    }

    private static String value(List<String> args, int i, String flag) {
        if (i >= args.size() || args.get(i).startsWith("--")) {
            throw new InvalidOptionsException(flag + " needs a value");
        }
        return args.get(i);
    }

    private static int intValue(List<String> args, int i, String flag, int min, int max) {
        String raw = value(args, i, flag);
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new InvalidOptionsException(flag + " must be a number, got '" + raw + "'");
        }
        if (n < min || n > max) {
            throw new InvalidOptionsException(flag + " must be between " + min + " and " + max + ", got " + n);
        }
        return n;
    }
}
