package com.projectardor.jobsearch.discovery.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads only facts a page actually declares (schema.org JobPosting or meta tags).
 * Missing salary, experience or deadline stays null; nothing is inferred.
 */
@Component
public class JobPageParser {

    private static final Pattern JSON_LD = Pattern.compile(
            "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record ParsedJob(String company, String title, String location, String salaryText,
            String experienceText, String deadlineText) {
        public ParsedJob merge(ParsedJob fallback) {
            if (fallback == null) return this;
            return new ParsedJob(firstNonBlank(company, fallback.company()),
                    firstNonBlank(title, fallback.title()),
                    firstNonBlank(location, fallback.location()),
                    firstNonBlank(salaryText, fallback.salaryText()),
                    firstNonBlank(experienceText, fallback.experienceText()),
                    firstNonBlank(deadlineText, fallback.deadlineText()));
        }
    }

    public ParsedJob parse(String html) {
        if (html == null || html.isBlank()) return new ParsedJob(null, null, null, null, null, null);
        return parseJsonLd(html).map(job -> job.merge(parseMeta(html))).orElseGet(() -> parseMeta(html));
    }

    Optional<ParsedJob> parseJsonLd(String html) {
        Matcher scripts = JSON_LD.matcher(html);
        while (scripts.find()) {
            JsonNode root;
            try {
                root = JSON.readTree(scripts.group(1));
            } catch (RuntimeException malformed) {
                continue;
            }
            Optional<JsonNode> posting = findJobPosting(root);
            if (posting.isPresent()) return Optional.of(readPosting(posting.get()));
        }
        return Optional.empty();
    }

    ParsedJob parseMeta(String html) {
        String ogTitle = metaContent(html, "og:title");
        String twitterTitle = metaContent(html, "twitter:title");
        String pageTitle = TITLE.matcher(html).results().findFirst()
                .map(result -> clean(result.group(1))).orElse(null);
        // og:site_name is a site brand (often the board or ATS), not proof of the employer.
        return new ParsedJob(null, firstNonBlank(ogTitle, twitterTitle, pageTitle),
                null, null, null, null);
    }

    private Optional<JsonNode> findJobPosting(JsonNode node) {
        if (node == null || node.isNull()) return Optional.empty();
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<JsonNode> found = findJobPosting(item);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }
        if (!node.isObject()) return Optional.empty();
        JsonNode type = node.get("@type");
        if (type != null) {
            if (type.isTextual() && type.asText().equalsIgnoreCase("JobPosting")) return Optional.of(node);
            if (type.isArray() && java.util.stream.StreamSupport.stream(type.spliterator(), false)
                    .anyMatch(item -> item.isTextual() && item.asText().equalsIgnoreCase("JobPosting"))) {
                return Optional.of(node);
            }
        }
        JsonNode graph = node.get("@graph");
        if (graph != null) {
            Optional<JsonNode> found = findJobPosting(graph);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private ParsedJob readPosting(JsonNode posting) {
        String company = text(posting.get("hiringOrganization"), "name");
        if (company == null && posting.path("hiringOrganization").isTextual()) {
            company = posting.path("hiringOrganization").asText();
        }
        String title = text(posting, "title");
        String location = location(posting.get("jobLocation"));
        String salary = salary(posting.get("baseSalary"));
        String experience = experience(posting.get("experienceRequirements"));
        String deadline = text(posting, "validThrough");
        return new ParsedJob(trim(company), trim(title), trim(location), trim(salary), trim(experience), trim(deadline));
    }

    private String location(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = location(item);
                if (value != null) values.add(value);
            }
            return values.isEmpty() ? null : String.join(" / ", values);
        }
        JsonNode address = node.path("address");
        if (!address.isObject()) return null;
        List<String> parts = new ArrayList<>();
        for (String field : List.of("addressLocality", "addressRegion", "addressCountry")) {
            String value = text(address, field);
            if (value != null && !parts.contains(value)) parts.add(value);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String salary(JsonNode node) {
        if (node == null || node.isNull()) return null;
        JsonNode value = node.path("value");
        if (value.isNumber()) return value.asText();
        if (value.isObject()) {
            String currency = text(node, "currency");
            String min = text(value, "minValue"), max = text(value, "maxValue"), single = text(value, "value");
            String unit = text(value, "unitText");
            String range = max != null && min != null ? min + "–" + max : firstNonBlank(single, min, max);
            if (range == null) return null;
            return String.join(" ", java.util.stream.Stream.of(currency, range, unit)
                    .filter(part -> part != null && !part.isBlank()).toList());
        }
        return null;
    }

    private String experience(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.asText();
        String months = text(node, "monthsOfExperience");
        if (months != null) return "至少 " + months + " 个月经验";
        if (node.isArray() && !node.isEmpty()) return experience(node.get(0));
        return null;
    }

    private String metaContent(String html, String key) {
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            String property = attribute(tag, "property");
            String name = attribute(tag, "name");
            if (!key.equalsIgnoreCase(property == null ? "" : property)
                    && !key.equalsIgnoreCase(name == null ? "" : name)) continue;
            String content = attribute(tag, "content");
            if (content != null && !content.isBlank()) return clean(content);
        }
        return null;
    }

    private String attribute(String tag, String name) {
        Matcher attributes = ATTRIBUTE.matcher(tag);
        while (attributes.find()) {
            if (!name.equalsIgnoreCase(attributes.group(1))) continue;
            return attributes.group(3) != null ? attributes.group(3) : attributes.group(4);
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    private static String clean(String value) {
        return value.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").strip();
    }
}
