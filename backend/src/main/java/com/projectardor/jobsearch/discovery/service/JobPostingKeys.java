package com.projectardor.jobsearch.discovery.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical identities for discovered postings. Tracking parameters and scheme
 * differences must not create duplicates, while unrelated postings may never be
 * merged just because company and title look alike.
 */
public final class JobPostingKeys {

    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "gclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "dclid", "yclid",
            "ref", "refid", "referral", "referrer", "source", "src", "trk", "tracking",
            "gh_src", "lever-source", "lever-origin", "ashby_jid", "jobboard", "from", "share");
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern WORKDAY_REQUISITION = Pattern.compile("(?i)(?:^|[_/-])(R\\d{3,}|\\d{5,})(?:$|[/?])");

    private JobPostingKeys() {
    }

    /** Returns the persisted dedupe key; throws when the URL cannot be an application link. */
    public static String canonicalKey(String rawUrl) {
        URI uri = parse(rawUrl);
        String ats = atsKey(uri);
        if (ats != null) return ats;
        return "url:" + uri.getHost().toLowerCase(Locale.ROOT) + normalizedPath(uri) + normalizedQuery(uri);
    }

    /** A source label shown to the user: the public host that carried the link. */
    public static String sourceHost(String rawUrl) {
        return parse(rawUrl).getHost().toLowerCase(Locale.ROOT);
    }

    public static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("岗位链接不能为空");
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("岗位链接不是有效的 URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("岗位链接必须是公开的 HTTP 或 HTTPS 地址");
        }
        return uri;
    }

    /** Lower is better: prefer the employer's own or an ATS page over aggregators. */
    public static int sourcePriority(String rawUrl) {
        String host = parse(rawUrl).getHost().toLowerCase(Locale.ROOT);
        if (isAtsHost(host)) return 0;
        if (isAggregatorHost(host)) return 2;
        return 1;
    }

    public static boolean isAggregatorHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.contains("linkedin.")
                || value.contains("indeed.")
                || value.contains("glassdoor.")
                || value.contains("monster.")
                || value.contains("zhipin.com")
                || value.contains("lagou.com")
                || value.contains("liepin.com")
                || value.contains("51job.com")
                || value.contains("zhaopin.com")
                || value.contains("maimai.cn")
                || value.contains("wellfound.com")
                || value.contains("angel.co");
    }

    private static boolean isAtsHost(String host) {
        return host.endsWith("greenhouse.io")
                || host.endsWith("lever.co")
                || host.endsWith("ashbyhq.com")
                || host.endsWith("smartrecruiters.com")
                || host.endsWith("myworkdayjobs.com")
                || host.endsWith("icims.com");
    }

    private static String atsKey(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<String> segments = segments(uri.getPath());
        if (host.endsWith("greenhouse.io")) {
            int jobs = segments.indexOf("jobs");
            if (jobs >= 0 && jobs + 1 < segments.size() && segments.get(jobs + 1).matches("\\d{3,}")) {
                return "greenhouse:" + segments.get(jobs + 1);
            }
        }
        if (host.endsWith("lever.co") && segments.size() >= 2) {
            Matcher uuid = UUID.matcher(segments.get(1));
            if (uuid.matches()) return "lever:" + uuid.group().toLowerCase(Locale.ROOT);
        }
        if (host.endsWith("ashbyhq.com") && segments.size() >= 2) {
            Matcher uuid = UUID.matcher(segments.get(1));
            if (uuid.matches()) return "ashby:" + uuid.group().toLowerCase(Locale.ROOT);
        }
        if (host.endsWith("smartrecruiters.com") && segments.size() >= 2) {
            String company = segments.get(0), posting = segments.get(1);
            if (!company.isBlank() && posting.matches("[\\w-]{3,}")) {
                return "smartrecruiters:" + company.toLowerCase(Locale.ROOT) + ":" + posting;
            }
        }
        if (host.endsWith("myworkdayjobs.com")) {
            for (int index = segments.size() - 1; index >= 0; index--) {
                Matcher requisition = WORKDAY_REQUISITION.matcher(segments.get(index));
                if (requisition.find()) return "workday:" + host + ":" + requisition.group(1).toUpperCase(Locale.ROOT);
            }
        }
        if (host.endsWith("icims.com")) {
            int jobs = segments.indexOf("jobs");
            if (jobs >= 0 && jobs + 1 < segments.size() && segments.get(jobs + 1).matches("\\d+")) {
                return "icims:" + host + ":" + segments.get(jobs + 1);
            }
        }
        return null;
    }

    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        if (path == null) return result;
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) result.add(segment);
        }
        return result;
    }

    private static String normalizedPath(URI uri) {
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String normalizedQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return "";
        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            String lower = decode(key).toLowerCase(Locale.ROOT);
            if (lower.startsWith("utm_") || TRACKING_PARAMETERS.contains(lower)) continue;
            kept.add(pair);
        }
        if (kept.isEmpty()) return "";
        kept.sort(Comparator.naturalOrder());
        return "?" + String.join("&", kept);
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }
}
