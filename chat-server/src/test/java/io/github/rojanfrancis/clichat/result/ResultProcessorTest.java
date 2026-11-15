package io.github.rojanfrancis.clichat.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rojanfrancis.clichat.ai.GeminiClient;
import io.github.rojanfrancis.clichat.exec.ExecutionResult;
import io.github.rojanfrancis.clichat.intent.TaskIntent;
import io.github.rojanfrancis.clichat.intent.TaskIntent.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultProcessorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultProcessor processor = new ResultProcessor(mapper,
            new Summarizer(new GeminiClient("", "test", mapper), mapper));
    private final TaskIntent intent = new TaskIntent(Action.CRAWL_SITE, "https://acme.test", 5, 1,
            true, true, "contact details", "rules", null);

    private static final String REPORT = """
            {"tool":"sitecrawl","version":"1.0.0","status":"%s","startUrl":"https://acme.test",
             "startedAt":"2026-11-16T10:00:00Z","durationMs":900,"pagesCrawled":1,
             "site":{"title":"Acme Tools","description":"Acme sells hand tools.","language":"en",
                     "emails":["sales@acme.test"],"socialLinks":[]},
             "pages":[{"url":"https://acme.test","statusCode":200,"depth":0,"title":"Acme Tools",
                       "description":"Acme sells hand tools.","headings":[{"level":"h1","text":"Welcome to Acme"}],
                       "wordCount":120,"excerpt":"Acme sells hand tools.","internalLinks":4,"externalLinks":1}],
             "issues":[]}""";

    private static ExecutionResult exec(int exit, String stdout) {
        return new ExecutionResult("sitecrawl --url https://acme.test", 42, exit, false, false, 900, stdout, false,
                List.of("[sitecrawl] done"));
    }

    @Test
    void successBuildsSummaryAndCleanJson() {
        TaskOutcome o = processor.process(intent, exec(0, REPORT.formatted("success")));

        assertThat(o.status()).isEqualTo("success");
        assertThat(o.summary()).contains("Acme Tools", "hand tools", "sales@acme.test");
        assertThat(o.summarySource()).startsWith("extractive");
        assertThat(o.result().path("site").path("contactEmails").get(0).asText()).isEqualTo("sales@acme.test");
        assertThat(o.result().path("pages").get(0).path("headings").get(0).asText()).isEqualTo("Welcome to Acme");
        assertThat(o.execution().exitCode()).isZero();
    }

    @Test
    void exitCodeThatContradictsTheReportIsNotTrusted() {
        TaskOutcome o = processor.process(intent, exec(3, REPORT.formatted("success")));

        assertThat(o.status()).isEqualTo("error");
        assertThat(o.result()).isNull();
        assertThat(o.headline()).contains("does not match");
    }

    @Test
    void outputThatIsNotJsonIsReportedAsAnError() {
        TaskOutcome o = processor.process(intent, exec(0, "Segmentation fault"));

        assertThat(o.status()).isEqualTo("error");
        assertThat(o.headline()).contains("did not print a valid JSON report");
    }

    @Test
    void successWithoutPagesIsNotShownAsSuccess() throws Exception {
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(REPORT.formatted("success"));
        report.putArray("pages");
        TaskOutcome o = processor.process(intent, exec(0, mapper.writeValueAsString(report)));

        assertThat(o.status()).isEqualTo("error");
    }

    @Test
    void timeoutAndCancelAreReportedHonestly() {
        ExecutionResult timedOut = new ExecutionResult("x", 1, -1, true, false, 75000, "", false, List.of());
        ExecutionResult cancelled = new ExecutionResult("x", 1, 143, false, true, 2000, "", false, List.of());

        assertThat(processor.process(intent, timedOut).status()).isEqualTo("timeout");
        assertThat(processor.process(intent, cancelled).status()).isEqualTo("cancelled");
    }

    @Test
    void refusedInputShowsTheCrawlersReason() {
        String rejected = "{\"tool\":\"sitecrawl\",\"status\":\"rejected\",\"error\":\"Only http and https URLs are allowed\"}";
        TaskOutcome o = processor.process(intent, exec(4, rejected));

        assertThat(o.status()).isEqualTo("rejected");
        assertThat(o.headline()).contains("Only http and https URLs are allowed");
    }
}
