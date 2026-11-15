package io.github.rojanfrancis.sitecrawl;

import java.util.List;

/** The JSON document the CLI prints to stdout. */
public record CrawlReport(
        String tool,
        String version,
        String status,          // success | partial | failed | blocked | rejected
        String startUrl,
        String startedAt,
        long durationMs,
        Limits limits,
        int pagesCrawled,
        Site site,
        List<Page> pages,
        List<Issue> issues) {

    public record Limits(int maxPages, int maxDepth, int timeoutSeconds, int maxSeconds, boolean robotsTxtRespected) {}

    public record Site(String title, String description, String language,
                       List<String> emails, List<String> socialLinks) {}

    public record Heading(String level, String text) {}

    public record Page(String url, int statusCode, int depth, String title, String description,
                       List<Heading> headings, int wordCount, String excerpt,
                       int internalLinks, int externalLinks) {}

    /** A page that could not be used, and why. */
    public record Issue(String url, String reason) {}
}
