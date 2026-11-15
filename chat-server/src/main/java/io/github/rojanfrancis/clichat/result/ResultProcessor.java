package io.github.rojanfrancis.clichat.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rojanfrancis.clichat.exec.ExecutionResult;
import io.github.rojanfrancis.clichat.intent.TaskIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Checks what the CLI produced and turns it into the chat answer. The exit code and the
 * report's own status must agree, and a success must contain real pages; anything else is
 * reported as an error rather than shown as a result.
 */
@Component
public class ResultProcessor {

    private static final Map<Integer, String> EXPECTED_STATUS = Map.of(
            0, "success", 2, "partial", 3, "failed", 4, "rejected", 5, "blocked", 1, "failed");

    private final ObjectMapper mapper;
    private final Summarizer summarizer;

    public ResultProcessor(ObjectMapper mapper, Summarizer summarizer) {
        this.mapper = mapper;
        this.summarizer = summarizer;
    }

    public TaskOutcome process(TaskIntent intent, ExecutionResult exec) {
        if (exec.timedOut()) {
            return failure("timeout", "The crawler did not finish in time and was stopped. No result was produced.", null, exec);
        }
        if (exec.cancelled()) {
            return failure("cancelled", "You cancelled the task, so the crawler was stopped before it finished.", null, exec);
        }

        JsonNode report;
        try {
            report = mapper.readTree(exec.stdout());
        } catch (Exception e) {
            report = null;
        }
        if (report == null || !report.isObject() || !"sitecrawl".equals(report.path("tool").asText())) {
            return failure("error", "The CLI exited with code " + exec.exitCode()
                    + " but did not print a valid JSON report, so there is no result to show.", null, exec);
        }

        String reported = report.path("status").asText();
        String expected = EXPECTED_STATUS.get(exec.exitCode());
        if (expected == null || !expected.equals(reported)) {
            return failure("error", "The CLI's exit code (" + exec.exitCode() + ") does not match its report status ("
                    + reported + "), so the result is not trusted.", report, exec);
        }

        switch (reported) {
            case "rejected" -> {
                return failure("rejected", "The crawler refused the input: " + report.path("error").asText(), report, exec);
            }
            case "blocked" -> {
                return failure("blocked", "This site's robots.txt does not allow crawling that page, so I did not crawl it.", report, exec);
            }
            case "failed" -> {
                String why = report.has("error") ? report.path("error").asText() : firstIssue(report);
                return failure("failed", "The crawl failed: " + why, report, exec);
            }
            default -> { /* success or partial, checked below */ }
        }

        JsonNode pages = report.path("pages");
        if (!pages.isArray() || pages.isEmpty()) {
            return failure("error", "The CLI reported " + reported + " but returned no pages.", report, exec);
        }

        Summarizer.Summary summary = intent.wantSummary() || intent.wantJson()
                ? summarizer.summarize(report, intent.focus())
                : null;
        ObjectNode result = buildResult(report, summary);
        String headline = "partial".equals(reported)
                ? "Partly done: crawled " + pages.size() + " page(s), " + report.path("issues").size() + " could not be used."
                : "Done: crawled " + pages.size() + " page(s) of " + report.path("startUrl").asText() + ".";
        return new TaskOutcome(reported, headline,
                summary == null ? null : summary.text(),
                summary == null ? List.of() : summary.keyPoints(),
                summary == null ? null : summary.source(),
                intent.wantJson() ? result : null, report, execution(exec));
    }

    /** The JSON the user asked for: clearly named fields, no internal noise. */
    ObjectNode buildResult(JsonNode report, Summarizer.Summary summary) {
        ObjectNode out = mapper.createObjectNode();
        out.put("url", report.path("startUrl").asText());
        out.put("status", report.path("status").asText());
        out.put("crawledAt", report.path("startedAt").asText());
        out.put("pagesCrawled", report.path("pagesCrawled").asInt());
        JsonNode site = report.path("site");
        ObjectNode s = out.putObject("site");
        s.set("title", site.path("title"));
        s.set("description", site.path("description"));
        s.set("language", site.path("language"));
        s.set("contactEmails", site.path("emails"));
        s.set("socialLinks", site.path("socialLinks"));
        if (summary != null) {
            out.put("summary", summary.text());
            ArrayNode kp = out.putArray("keyPoints");
            summary.keyPoints().forEach(kp::add);
        }
        ArrayNode pages = out.putArray("pages");
        for (JsonNode p : report.path("pages")) {
            ObjectNode page = pages.addObject();
            page.put("url", p.path("url").asText());
            page.put("httpStatus", p.path("statusCode").asInt());
            page.set("title", p.path("title"));
            page.set("description", p.path("description"));
            ArrayNode hs = page.putArray("headings");
            p.path("headings").forEach(h -> hs.add(h.path("text").asText()));
            page.put("wordCount", p.path("wordCount").asInt());
            page.put("linksOnPage", p.path("internalLinks").asInt() + p.path("externalLinks").asInt());
        }
        out.set("issues", report.path("issues"));
        return out;
    }

    private TaskOutcome failure(String status, String headline, JsonNode report, ExecutionResult exec) {
        return new TaskOutcome(status, headline, null, List.of(), null, null, report, execution(exec));
    }

    private static String firstIssue(JsonNode report) {
        JsonNode issues = report.path("issues");
        return issues.size() > 0 ? issues.get(0).path("reason").asText() : "no page could be fetched";
    }

    private static TaskOutcome.Execution execution(ExecutionResult e) {
        return new TaskOutcome.Execution(e.command(), e.pid(), e.exitCode(), e.exitMeaning(), e.durationMs(),
                e.timedOut(), e.cancelled(), e.stderr().size(), e.stdoutTruncated());
    }
}
