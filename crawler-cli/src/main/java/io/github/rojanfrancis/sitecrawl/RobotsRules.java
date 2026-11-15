package io.github.rojanfrancis.sitecrawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small robots.txt reader: the rules for our user agent if the file names it, otherwise
 * the rules for "*". The longest matching Allow or Disallow prefix wins, as in RFC 9309.
 * Wildcards are not supported; a rule containing '*' or '$' is matched on the text before it.
 */
public class RobotsRules {

    private record Rule(String prefix, boolean allow) {}

    private final List<Rule> rules;

    private RobotsRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static RobotsRules allowAll() {
        return new RobotsRules(List.of());
    }

    public static RobotsRules parse(String robotsTxt, String userAgent) {
        String agent = userAgent.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        List<String> groupAgents = new ArrayList<>();
        boolean lastWasAgent = false;

        for (String rawLine : robotsTxt.split("\\R")) {
            String line = rawLine.replaceAll("#.*", "").trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if (key.equals("user-agent")) {
                if (!lastWasAgent) {
                    groupAgents.clear();
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            if (!key.equals("allow") && !key.equals("disallow")) {
                continue;
            }
            if (value.isEmpty()) {
                continue; // "Disallow:" with no path allows everything
            }
            int wild = indexOfAny(value);
            Rule rule = new Rule(wild >= 0 ? value.substring(0, wild) : value, key.equals("allow"));
            for (String g : groupAgents) {
                if (g.equals("*")) {
                    wildcard.add(rule);
                } else if (agent.contains(g)) {
                    specific.add(rule);
                }
            }
        }
        return new RobotsRules(specific.isEmpty() ? wildcard : specific);
    }

    public boolean allows(String path) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        Rule best = null;
        for (Rule r : rules) {
            if (p.startsWith(r.prefix()) && (best == null || r.prefix().length() > best.prefix().length()
                    || (r.prefix().length() == best.prefix().length() && r.allow()))) {
                best = r;
            }
        }
        return best == null || best.allow();
    }

    private static int indexOfAny(String s) {
        int star = s.indexOf('*');
        int dollar = s.indexOf('$');
        if (star < 0) return dollar;
        if (dollar < 0) return star;
        return Math.min(star, dollar);
    }
}
