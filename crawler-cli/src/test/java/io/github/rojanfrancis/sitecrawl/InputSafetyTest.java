package io.github.rojanfrancis.sitecrawl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputSafetyTest {

    private final UrlGuard guard = new UrlGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/admin", "http://127.0.0.1:8080", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.5", "http://192.168.1.1", "http://[::1]/", "http://0.0.0.0"})
    void refusesLocalAndPrivateHosts(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(CrawlOptions.InvalidOptionsException.class)
                .hasMessageContaining("private or local");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "file:///etc/passwd", "javascript:alert(1)", "https://user:pw@example.com"})
    void refusesOtherSchemesAndCredentials(String url) {
        assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(CrawlOptions.InvalidOptionsException.class);
    }

    @Test
    void rejectsOutOfRangeLimits() {
        assertThatThrownBy(() -> CrawlOptions.parse(List.of("--url", "https://93.184.215.14", "--max-pages", "500"), guard))
                .hasMessageContaining("--max-pages must be between 1 and 25");
        assertThatThrownBy(() -> CrawlOptions.parse(List.of("--url", "https://93.184.215.14", "--shell", "ls"), guard))
                .hasMessageContaining("Unknown option");
    }

    @Test
    void invalidInputStillPrintsJsonWithExitCode4() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = SiteCrawlCli.run(List.of("--url", "http://localhost"),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()), guard);

        assertThat(exit).isEqualTo(SiteCrawlCli.EXIT_INVALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"status\" : \"rejected\"");
    }

    @Test
    void robotsLongestMatchWinsAndAllowBeatsDisallowOnTies() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /private
                Allow: /private/press
                Disallow: /tmp*
                """, Crawler.USER_AGENT);

        assertThat(rules.allows("/")).isTrue();
        assertThat(rules.allows("/private/staff")).isFalse();
        assertThat(rules.allows("/private/press/2026")).isTrue();
        assertThat(rules.allows("/tmp-files")).isFalse();
    }

    @Test
    void robotsRulesForOurAgentReplaceTheWildcardGroup() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /

                User-agent: sitecrawl
                Disallow: /admin
                """, Crawler.USER_AGENT);

        assertThat(rules.allows("/docs")).isTrue();
        assertThat(rules.allows("/admin/users")).isFalse();
    }
}
