package io.github.rojanfrancis.sitecrawl;

import io.github.rojanfrancis.sitecrawl.CrawlReport.Heading;
import io.github.rojanfrancis.sitecrawl.CrawlReport.Issue;
import io.github.rojanfrancis.sitecrawl.CrawlReport.Page;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breadth-first crawl of one site: start page first, then same-host links up to the depth
 * and page limits. Redirects are followed by hand so each hop goes through {@link UrlGuard}.
 */
public class Crawler {

    public static final String VERSION = "1.0.0";
    static final String USER_AGENT = "sitecrawl/" + VERSION + " (BuildSprint CLI task demo)";

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final long POLITENESS_DELAY_MS = 250;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SKIPPED_EXTENSIONS = Pattern.compile(
            ".*\\.(pdf|jpe?g|png|gif|svg|webp|ico|zip|gz|tar|mp4|mp3|mov|avi|css|js|json|xml|woff2?|ttf|exe|dmg)$");
    private static final List<String> SOCIAL_HOSTS = List.of(
            "twitter.com", "x.com", "linkedin.com", "github.com", "facebook.com",
            "instagram.com", "youtube.com", "medium.com");

    private final UrlGuard guard;
    private final PrintStream log;

    public Crawler(UrlGuard guard, PrintStream log) {
        this.guard = guard;
        this.log = log;
    }

    private record Fetched(URI finalUrl, int status, Document doc) {}

    private record Queued(URI url, int depth) {}

    public CrawlReport crawl(CrawlOptions options) {
        long start = System.currentTimeMillis();
        long deadline = start + options.maxSeconds() * 1000L;
        URI startUrl = options.startUrl();
        String host = startUrl.getHost().toLowerCase(Locale.ROOT);

        List<Page> pages = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Set<String> emails = new LinkedHashSet<>();
        Set<String> socials = new LinkedHashSet<>();
        String language = null;

        RobotsRules robots = loadRobots(startUrl, options);
        if (!robots.allows(pathOf(startUrl))) {
            log("robots.txt disallows " + startUrl + ", stopping");
            issues.add(new Issue(startUrl.toString(), "Blocked by the site's robots.txt"));
            return report("blocked", options, start, pages, issues, null, emails, socials);
        }

        Deque<Queued> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new Queued(startUrl, 0));
        seen.add(key(startUrl));
        boolean budgetHit = false;

        while (!queue.isEmpty() && pages.size() < options.maxPages()) {
            if (System.currentTimeMillis() > deadline) {
                log("time budget of " + options.maxSeconds() + "s used up, stopping with what we have");
                issues.add(new Issue(queue.peek().url().toString(), "Not crawled: time budget used up"));
                budgetHit = true;
                break;
            }
            Queued next = queue.poll();
            if (!robots.allows(pathOf(next.url()))) {
                log("skip " + next.url() + " (robots.txt)");
                issues.add(new Issue(next.url().toString(), "Skipped: disallowed by robots.txt"));
                continue;
            }
            if (!pages.isEmpty()) {
                sleep(POLITENESS_DELAY_MS);
            }

            Fetched fetched;
            long t0 = System.currentTimeMillis();
            try {
                fetched = fetch(next.url(), options.timeoutSeconds());
            } catch (CrawlOptions.InvalidOptionsException e) {
                log("refused " + next.url() + ": " + e.getMessage());
                issues.add(new Issue(next.url().toString(), "Refused: " + e.getMessage()));
                continue;
            } catch (IOException e) {
                log("error " + next.url() + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
                issues.add(new Issue(next.url().toString(), describe(e)));
                continue;
            }
            long ms = System.currentTimeMillis() - t0;
            log("fetched " + fetched.finalUrl() + " -> HTTP " + fetched.status() + " (" + ms + " ms)");

            if (fetched.status() >= 400) {
                issues.add(new Issue(next.url().toString(), "HTTP " + fetched.status()));
                continue;
            }
            if (fetched.doc() == null) {
                issues.add(new Issue(next.url().toString(), "Not an HTML page"));
                continue;
            }

            Document doc = fetched.doc();
            if (language == null) {
                String lang = doc.select("html").attr("lang");
                language = lang.isBlank() ? null : lang;
            }
            int internal = 0, external = 0;
            for (Element a : doc.select("a[href]")) {
                String href = a.absUrl("href");
                if (href.startsWith("mailto:")) {
                    String mail = href.substring(7).split("\\?")[0];
                    if (EMAIL.matcher(mail).matches()) emails.add(mail.toLowerCase(Locale.ROOT));
                    continue;
                }
                URI link = toUri(href);
                if (link == null) continue;
                String linkHost = link.getHost().toLowerCase(Locale.ROOT);
                if (sameSite(linkHost, host)) {
                    internal++;
                    if (next.depth() < options.maxDepth() && !SKIPPED_EXTENSIONS.matcher(pathOf(link).toLowerCase(Locale.ROOT)).matches()
                            && seen.add(key(link))) {
                        queue.add(new Queued(link, next.depth() + 1));
                    }
                } else {
                    external++;
                    for (String s : SOCIAL_HOSTS) {
                        if (linkHost.equals(s) || linkHost.endsWith("." + s)) {
                            socials.add(stripFragment(link).toString());
                        }
                    }
                }
            }
            String text = mainText(doc);
            Matcher m = EMAIL.matcher(text);
            while (m.find() && emails.size() < 20) {
                emails.add(m.group().toLowerCase(Locale.ROOT));
            }
            pages.add(new Page(fetched.finalUrl().toString(), fetched.status(), next.depth(),
                    blankToNull(doc.title()), metaDescription(doc), headings(doc),
                    text.isBlank() ? 0 : text.split("\\s+").length, excerpt(text, 700), internal, external));
        }

        String status;
        if (pages.isEmpty()) {
            status = "failed";
        } else if (budgetHit || !issues.isEmpty()) {
            status = "partial";
        } else {
            status = "success";
        }
        log("done: " + pages.size() + " page(s), " + issues.size() + " issue(s), status " + status);
        return report(status, options, start, pages, issues, language, emails, socials);
    }

    private CrawlReport report(String status, CrawlOptions o, long start, List<Page> pages, List<Issue> issues,
                               String language, Set<String> emails, Set<String> socials) {
        Page first = pages.isEmpty() ? null : pages.get(0);
        CrawlReport.Site site = new CrawlReport.Site(
                first == null ? null : first.title(),
                first == null ? null : first.description(),
                language,
                List.copyOf(emails).subList(0, Math.min(emails.size(), 10)),
                List.copyOf(socials).subList(0, Math.min(socials.size(), 10)));
        return new CrawlReport("sitecrawl", VERSION, status, o.startUrl().toString(),
                Instant.ofEpochMilli(start).toString(), System.currentTimeMillis() - start,
                new CrawlReport.Limits(o.maxPages(), o.maxDepth(), o.timeoutSeconds(), o.maxSeconds(), true),
                pages.size(), site, pages, issues);
    }

    private Fetched fetch(URI url, int timeoutSeconds) throws IOException {
        URI current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            guard.checkHost(current.getHost());
            Connection.Response res = Jsoup.connect(current.toString())
                    .userAgent(USER_AGENT)
                    .timeout(timeoutSeconds * 1000)
                    .maxBodySize(MAX_BODY_BYTES)
                    .followRedirects(false)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();
            int code = res.statusCode();
            if (code >= 300 && code < 400 && res.hasHeader("Location")) {
                URI target = current.resolve(res.header("Location"));
                String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new CrawlOptions.InvalidOptionsException("redirect to a non-http URL");
                }
                log("redirect " + current + " -> " + target);
                current = target;
                continue;
            }
            String type = res.contentType() == null ? "" : res.contentType().toLowerCase(Locale.ROOT);
            boolean html = type.contains("text/html") || type.contains("application/xhtml");
            return new Fetched(current, code, html && code < 400 ? res.parse() : null);
        }
        throw new IOException("too many redirects");
    }

    private RobotsRules loadRobots(URI start, CrawlOptions options) {
        URI robotsUrl = start.resolve("/robots.txt");
        try {
            Fetched f = fetchRaw(robotsUrl, options.timeoutSeconds());
            if (f == null) {
                log("no robots.txt, all paths allowed");
                return RobotsRules.allowAll();
            }
            log("read robots.txt");
            return RobotsRules.parse(f.doc().wholeText(), USER_AGENT);
        } catch (Exception e) {
            log("could not read robots.txt (" + e.getMessage() + "), treating all paths as allowed");
            return RobotsRules.allowAll();
        }
    }

    private Fetched fetchRaw(URI url, int timeoutSeconds) throws IOException {
        guard.checkHost(url.getHost());
        Connection.Response res = Jsoup.connect(url.toString())
                .userAgent(USER_AGENT).timeout(timeoutSeconds * 1000).maxBodySize(512 * 1024)
                .followRedirects(false).ignoreHttpErrors(true).ignoreContentType(true).execute();
        if (res.statusCode() >= 300) { // missing, or redirected elsewhere: treat as no rules
            return null;
        }
        Document plain = Jsoup.parse(res.body(), "", org.jsoup.parser.Parser.xmlParser());
        return new Fetched(url, res.statusCode(), plain);
    }

    private static String mainText(Document doc) {
        doc.select("script, style, noscript, svg, nav, footer, form").remove();
        Element main = doc.selectFirst("main, article, [role=main]");
        Element root = main != null ? main : doc.body();
        return root == null ? "" : root.text().trim();
    }

    private static List<Heading> headings(Document doc) {
        List<Heading> list = new ArrayList<>();
        for (Element h : doc.select("h1, h2, h3")) {
            String t = h.text().trim();
            if (!t.isEmpty() && list.size() < 15) {
                list.add(new Heading(h.tagName(), t.length() > 160 ? t.substring(0, 160) + "…" : t));
            }
        }
        return list;
    }

    private static String metaDescription(Document doc) {
        String d = doc.select("meta[name=description]").attr("content");
        if (d.isBlank()) d = doc.select("meta[property=og:description]").attr("content");
        return blankToNull(d.trim());
    }

    private static String excerpt(String text, int max) {
        if (text.length() <= max) return text;
        int cut = text.lastIndexOf(' ', max);
        return text.substring(0, cut > 0 ? cut : max) + "…";
    }

    private static URI toUri(String href) {
        if (href == null || href.isBlank()) return null;
        try {
            URI u = new URI(href.replace(" ", "%20"));
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || u.getHost() == null) return null;
            return stripFragment(u);
        } catch (Exception e) {
            return null;
        }
    }

    private static URI stripFragment(URI u) {
        try {
            return new URI(u.getScheme(), u.getAuthority(), u.getPath(), u.getQuery(), null);
        } catch (Exception e) {
            return u;
        }
    }

    private static boolean sameSite(String linkHost, String host) {
        return linkHost.equals(host) || stripWww(linkHost).equals(stripWww(host));
    }

    private static String stripWww(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String key(URI u) {
        String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
        path = path.replaceFirst("/index\\.html?$", "/"); // "/" and "/index.html" are the same page
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return stripWww(u.getHost().toLowerCase(Locale.ROOT)) + path + (u.getQuery() == null ? "" : "?" + u.getQuery());
    }

    private static String pathOf(URI u) {
        String p = u.getRawPath();
        return (p == null || p.isEmpty()) ? "/" : p;
    }

    private static String describe(IOException e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("Timeout")) return "Timed out";
        if (name.contains("UnknownHost")) return "Host not found";
        if (name.contains("SSL")) return "TLS/SSL error";
        if (name.contains("Connect")) return "Could not connect";
        return name + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String message) {
        log.println("[sitecrawl] " + message);
        log.flush();
    }
}
