package io.github.rojanfrancis.sitecrawl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Crawls a small local site so the tests never depend on the internet. */
class CrawlerTest {

    private HttpServer server;
    private String base;
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();

    @BeforeEach
    void startSite() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> pages = Map.of(
                "/", """
                        <html lang="en"><head><title>Acme Tools</title>
                        <meta name="description" content="Acme sells hand tools."></head>
                        <body><main><h1>Welcome to Acme</h1><p>Write to sales@acme.test for quotes.</p>
                        <a href="/about">About</a> <a href="/missing">Old page</a> <a href="/private/x">Staff</a>
                        <a href="/logo.png">Logo</a> <a href="https://github.com/acme">GitHub</a></main></body></html>""",
                "/about", """
                        <html><head><title>About Acme</title></head><body><h2>Our story</h2>
                        <p>Founded in 1990.</p><a href="/about/team">Team</a></body></html>""",
                "/about/team", "<html><head><title>Team</title></head><body><h2>People</h2></body></html>",
                "/robots.txt", "User-agent: *\nDisallow: /private\n");
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = pages.get(path);
            int code = body == null ? 404 : 200;
            byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    path.endsWith(".txt") ? "text/plain" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CrawlReport crawl(int maxPages, int maxDepth) {
        UrlGuard guard = new UrlGuard(true);
        CrawlOptions options = new CrawlOptions(URI.create(base + "/"), maxPages, maxDepth, 5, 30);
        return new Crawler(guard, new PrintStream(logBytes, true, StandardCharsets.UTF_8)).crawl(options);
    }

    @Test
    void crawlsSameSitePagesAndReportsWhatFailed() {
        CrawlReport report = crawl(10, 1);

        assertThat(report.pages()).extracting(CrawlReport.Page::title).containsExactly("Acme Tools", "About Acme");
        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.issues()).extracting(CrawlReport.Issue::reason)
                .containsExactlyInAnyOrder("HTTP 404", "Skipped: disallowed by robots.txt");
        assertThat(report.site().description()).isEqualTo("Acme sells hand tools.");
        assertThat(report.site().language()).isEqualTo("en");
        assertThat(report.site().emails()).containsExactly("sales@acme.test");
        assertThat(report.site().socialLinks()).containsExactly("https://github.com/acme");
        assertThat(report.pages().get(0).headings()).extracting(CrawlReport.Heading::text).containsExactly("Welcome to Acme");
        assertThat(logBytes.toString(StandardCharsets.UTF_8)).contains("fetched", "HTTP 200");
    }

    @Test
    void depthZeroFetchesOnlyTheStartPage() {
        CrawlReport report = crawl(10, 0);

        assertThat(report.pages()).hasSize(1);
        assertThat(report.status()).isEqualTo("success");
    }

    @Test
    void stopsAtThePageLimit() {
        CrawlReport report = crawl(1, 2);

        assertThat(report.pagesCrawled()).isEqualTo(1);
        assertThat(report.limits().maxPages()).isEqualTo(1);
    }

    @Test
    void reportsFailureWhenTheStartPageIsMissing() {
        UrlGuard guard = new UrlGuard(true);
        CrawlOptions options = new CrawlOptions(URI.create(base + "/missing"), 3, 1, 5, 30);
        CrawlReport report = new Crawler(guard, new PrintStream(logBytes)).crawl(options);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.pages()).isEmpty();
        assertThat(report.issues()).extracting(CrawlReport.Issue::reason).containsExactly("HTTP 404");
    }

    @Test
    void cliPrintsJsonAndExitsWithTheMatchingCode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = SiteCrawlCli.run(List.of("--url", base + "/", "--max-depth", "0"),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(logBytes), new UrlGuard(true));

        assertThat(exit).isEqualTo(SiteCrawlCli.EXIT_SUCCESS);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"status\" : \"success\"", "\"title\" : \"Acme Tools\"");
    }
}
