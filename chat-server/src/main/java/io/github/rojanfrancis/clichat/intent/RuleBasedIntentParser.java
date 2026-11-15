package io.github.rojanfrancis.clichat.intent;

import io.github.rojanfrancis.clichat.intent.TaskIntent.Action;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic intent parser. It works with no API key and is also the fallback whenever
 * the LLM is unavailable or answers with something that fails validation.
 */
@Component
public class RuleBasedIntentParser {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "(?<![@\\w.-])((?:[a-z0-9-]+\\.)+[a-z]{2,})(/[^\\s<>\"'`]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGES = Pattern.compile("(\\d{1,3})\\s*(?:pages?|urls?|links?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPTH = Pattern.compile("depth\\s*(?:of\\s*)?(\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRAWL_WORDS = Pattern.compile(
            "\\b(crawl|scrape|extract|summari[sz]e|summary|analy[sz]e|read|visit|open|check|fetch|look at|go to|what is|what's on|info|information|details|json)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_WORDS = Pattern.compile(
            "^\\s*(hi|hello|hey|help|what can you do|how does this work)\\b", Pattern.CASE_INSENSITIVE);

    public TaskIntent parse(String message) {
        String text = message == null ? "" : message.trim();
        String url = findUrl(text);
        String lower = text.toLowerCase(Locale.ROOT);

        if (url == null) {
            if (HELP_WORDS.matcher(text).find()) {
                return TaskIntent.reply(Action.HELP, "rules", IntentService.HELP_TEXT);
            }
            if (CRAWL_WORDS.matcher(text).find()) {
                return TaskIntent.reply(Action.NEED_URL, "rules",
                        "I can do that, but I need a website address. Try: \"Crawl https://example.com and summarise it in JSON\".");
            }
            return TaskIntent.reply(Action.UNSUPPORTED, "rules",
                    "I can only run website tasks right now: give me a URL and ask me to crawl, summarise or extract information from it.");
        }

        int pages = 5;
        Matcher p = PAGES.matcher(text);
        if (p.find()) pages = Integer.parseInt(p.group(1));
        boolean singlePage = lower.matches(".*\\b(only|just) (this|the|that|one) page\\b.*") || lower.contains("single page");
        if (singlePage) pages = 1;
        int depth = singlePage ? 0 : 1;
        Matcher d = DEPTH.matcher(text);
        if (d.find()) depth = Integer.parseInt(d.group(1));

        boolean wantJson = lower.contains("json") || lower.contains("structured") || !lower.contains("summar");
        boolean wantSummary = lower.contains("summar") || lower.contains("overview") || lower.contains("about")
                || !lower.contains("json");

        return new TaskIntent(Action.CRAWL_SITE, url, pages, depth, wantSummary, wantJson, focusOf(lower), "rules", null);
    }

    static String findUrl(String text) {
        Matcher m = URL.matcher(text);
        if (m.find()) {
            return trimTrailingPunctuation(m.group());
        }
        Matcher b = BARE_DOMAIN.matcher(text);
        while (b.find()) {
            String candidate = b.group();
            String tld = b.group(1).substring(b.group(1).lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (tld.matches("(json|txt|md|html?|pdf|png|jpg|csv|js|java|py)")) continue; // file names, not hosts
            return "https://" + trimTrailingPunctuation(candidate);
        }
        return null;
    }

    private static String trimTrailingPunctuation(String url) {
        return url.replaceAll("[.,;:!?)\\]]+$", "");
    }

    private static String focusOf(String lower) {
        if (lower.contains("contact") || lower.contains("email")) return "contact details";
        if (lower.contains("price") || lower.contains("pricing")) return "pricing";
        if (lower.contains("product") || lower.contains("service")) return "products and services";
        if (lower.contains("team") || lower.contains("founder")) return "team";
        return null;
    }
}
