package io.github.rojanfrancis.sitecrawl;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides which URLs the crawler may touch. Only public http(s) hosts are allowed:
 * loopback, private, link-local (cloud metadata) and similar addresses are refused, so a
 * prompt cannot turn the crawler into a way to reach the server's own network.
 * The check runs on the start URL and again before every request, including redirects.
 */
public class UrlGuard {

    private final boolean allowPrivateHosts;

    public UrlGuard() {
        this(false);
    }

    /** {@code allowPrivateHosts} exists only so tests can crawl a local server. */
    UrlGuard(boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CrawlOptions.InvalidOptionsException("URL is empty");
        }
        if (raw.length() > 2048) {
            throw new CrawlOptions.InvalidOptionsException("URL is longer than 2048 characters");
        }
        URI uri;
        try {
            uri = new URI(raw.trim()).normalize();
        } catch (URISyntaxException e) {
            throw new CrawlOptions.InvalidOptionsException("Not a valid URL: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new CrawlOptions.InvalidOptionsException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new CrawlOptions.InvalidOptionsException("URL has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new CrawlOptions.InvalidOptionsException("URLs with credentials are not allowed");
        }
        checkHost(uri.getHost());
        return uri;
    }

    /** Resolves the host and rejects it if any of its addresses is not public. */
    public void checkHost(String host) {
        if (allowPrivateHosts) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new CrawlOptions.HostNotFoundException(host);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new CrawlOptions.InvalidOptionsException(
                        "Host " + host + " resolves to a private or local address, which is not allowed");
            }
        }
    }

    static boolean isNonPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        }
        int first = b[0] & 0xFF, second = b[1] & 0xFF;
        return first == 0                                   // 0.0.0.0/8
                || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier NAT
                || (first == 192 && second == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24
                || first >= 240;                            // reserved and broadcast
    }
}
