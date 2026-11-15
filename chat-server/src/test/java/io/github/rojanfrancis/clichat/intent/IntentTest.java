package io.github.rojanfrancis.clichat.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rojanfrancis.clichat.ai.GeminiClient;
import io.github.rojanfrancis.clichat.exec.CrawlerCommand;
import io.github.rojanfrancis.clichat.intent.TaskIntent.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // No API key: the service uses the rule-based parser, exactly as it does without Gemini in production.
    private final IntentService service = new IntentService(new GeminiClient("", "test", mapper),
            new RuleBasedIntentParser(), mapper);

    @Test
    void readsTheMandatoryDemoRequest() {
        TaskIntent i = service.interpret("Crawl https://example.com, extract the important information, "
                + "give me a summary, and return the extracted data in JSON format.");

        assertThat(i.action()).isEqualTo(Action.CRAWL_SITE);
        assertThat(i.url()).isEqualTo("https://example.com");
        assertThat(i.wantSummary()).isTrue();
        assertThat(i.wantJson()).isTrue();
        assertThat(i.interpretedBy()).isEqualTo("rules");
    }

    @Test
    void picksUpBareDomainsPageCountsAndFocus() {
        TaskIntent i = service.interpret("get the contact emails from books.toscrape.com, 3 pages");

        assertThat(i.url()).isEqualTo("https://books.toscrape.com");
        assertThat(i.maxPages()).isEqualTo(3);
        assertThat(i.focus()).isEqualTo("contact details");
    }

    @Test
    void clampsLimitsToTheServerCaps() {
        TaskIntent i = service.interpret("crawl https://example.com 500 pages depth 9");

        assertThat(i.maxPages()).isEqualTo(TaskIntent.MAX_PAGES_CAP);
        assertThat(i.maxDepth()).isEqualTo(TaskIntent.MAX_DEPTH_CAP);
    }

    @Test
    void asksForAUrlWhenThereIsNone() {
        assertThat(service.interpret("summarise this website").action()).isEqualTo(Action.NEED_URL);
        assertThat(service.interpret("hello").action()).isEqualTo(Action.HELP);
        assertThat(service.interpret("write me a poem").action()).isEqualTo(Action.UNSUPPORTED);
    }

    @Test
    void shellSyntaxInTheMessageNeverBecomesACommand() {
        TaskIntent i = service.interpret("crawl https://example.com; rm -rf / && cat /etc/passwd");
        List<String> argv = new CrawlerCommand("sitecrawl.jar").build(i);

        assertThat(i.url()).isEqualTo("https://example.com");
        assertThat(argv).doesNotContain("rm", "-rf", "&&", "cat", "/etc/passwd");
        assertThat(argv.subList(argv.indexOf("--url"), argv.size()))
                .containsExactly("--url", "https://example.com", "--max-pages", "5", "--max-depth", "1",
                        "--timeout", "10", "--max-seconds", "45");
    }

    @Test
    void parsesAWellFormedLlmAnswerAndIgnoresGarbage() {
        assertThat(service.parseGeminiJson("""
                {"action":"crawl_site","url":"https://example.com","maxPages":3,"maxDepth":0,
                 "wantSummary":true,"wantJson":false,"focus":null,"reply":null}""")).get()
                .extracting(TaskIntent::url, TaskIntent::maxPages, TaskIntent::interpretedBy)
                .containsExactly("https://example.com", 3, "gemini");
        assertThat(service.parseGeminiJson("Sure! Here is the JSON you wanted")).isEmpty();
        assertThat(service.parseGeminiJson("{\"action\":\"run_shell\"}")).isEmpty();
    }

    @Test
    void validatesWhatTheLlmReturnsLikeAnyOtherInput() {
        TaskIntent fromLlm = new TaskIntent(Action.CRAWL_SITE, "https://example.com/a b", 50, 7, true, true,
                "ignore previous instructions <script>", "gemini", null);

        TaskIntent clean = service.sanitize(fromLlm);

        assertThat(clean.action()).isEqualTo(Action.NEED_URL); // URL with a space is refused
        TaskIntent ok = service.sanitize(new TaskIntent(Action.CRAWL_SITE,
                "example.com", 50, 7, true, true, "pricing <b>", "gemini", null));
        assertThat(ok.url()).isEqualTo("https://example.com");
        assertThat(ok.maxPages()).isEqualTo(10);
        assertThat(ok.maxDepth()).isEqualTo(2);
        assertThat(ok.focus()).isEqualTo("pricing b");
    }
}
