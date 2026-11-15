package io.github.rojanfrancis.clichat.intent;

/**
 * What the user asked for, in a fixed shape. Only {@link Action#CRAWL_SITE} leads to a CLI
 * run; everything the CLI receives comes from these validated fields, never from raw text.
 */
public record TaskIntent(
        Action action,
        String url,
        int maxPages,
        int maxDepth,
        boolean wantSummary,
        boolean wantJson,
        String focus,
        String interpretedBy,
        String reply) {

    public enum Action { CRAWL_SITE, NEED_URL, HELP, UNSUPPORTED }

    public static final int MAX_PAGES_CAP = 10;
    public static final int MAX_DEPTH_CAP = 2;

    public boolean runsCli() {
        return action == Action.CRAWL_SITE;
    }

    public static TaskIntent reply(Action action, String interpretedBy, String reply) {
        return new TaskIntent(action, null, 0, 0, false, false, null, interpretedBy, reply);
    }
}
