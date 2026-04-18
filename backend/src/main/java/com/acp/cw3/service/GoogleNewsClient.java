package com.acp.cw3.service;

import com.acp.cw3.model.NewsArticle;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GoogleNewsClient {
    private final RestClient restClient;
    private final String googleBaseUrl;
    private final String guardianBaseUrl;
    private final String guardianApiKey;
    private final List<String> apFeedUrls;

    public GoogleNewsClient(
            RestClient restClient,
            @Value("${app.news.google-base-url:https://news.google.com/rss/search}") String googleBaseUrl,
            @Value("${app.news.guardian-base-url:https://content.guardianapis.com/search}") String guardianBaseUrl,
            @Value("${app.news.guardian-key:}") String guardianApiKey,
            @Value("${app.news.ap-politics-feed:https://feeds.apnews.com/apnews/politics}") String apPoliticsFeed,
            @Value("${app.news.ap-top-feed:https://feeds.apnews.com/apnews/topnews}") String apTopFeed
    ) {
        this.restClient = restClient;
        this.googleBaseUrl = googleBaseUrl;
        this.guardianBaseUrl = guardianBaseUrl;
        this.guardianApiKey = guardianApiKey;
        this.apFeedUrls = List.of(apPoliticsFeed, apTopFeed);
    }

    public List<NewsArticle> fetchStateNews(String stateName, int limit) {
        List<NewsArticle> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 1) Guardian API (stable + keyed)
        try {
            mergeUnique(merged, seen, fetchGuardian(stateName, limit), limit);
        } catch (Exception ignored) {
            // Continue to next provider.
        }
        if (merged.size() >= limit) {
            return merged;
        }

        // 2) AP RSS feed(s)
        try {
            mergeUnique(merged, seen, fetchAssociatedPress(stateName, limit), limit);
        } catch (Exception ignored) {
            // Continue to next provider.
        }
        if (merged.size() >= limit) {
            return merged;
        }

        // 3) Google News RSS fallback (more likely to trigger anti-automation)
        try {
            mergeUnique(merged, seen, fetchGoogle(stateName, limit), limit);
        } catch (Exception ignored) {
            // Return whatever we already collected.
        }
        return merged;
    }

    private List<NewsArticle> fetchGoogle(String stateName, int limit) {
        List<String> queries = List.of(
                "\"" + stateName + "\" senate election",
                "\"" + stateName + "\" senate race",
                "\"" + stateName + "\" election",
                stateName + " politics"
        );
        List<NewsArticle> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            mergeUnique(out, seen, fetchGoogleByQuery(query, limit), limit);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<NewsArticle> fetchGoogleByQuery(String queryText, int limit) {
        String query = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        String url = googleBaseUrl + "?q=" + query + "&hl=en-US&gl=US&ceid=US:en";
        String xml = restClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; CW3-MidTerm-Tracker/1.0)")
                .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                .retrieve()
                .body(String.class);
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        String lowered = xml.toLowerCase(Locale.ROOT);
        if (lowered.contains("we're sorry") || lowered.contains("automated queries")) {
            throw new IllegalStateException("Google News ratelimited");
        }
        if (!lowered.contains("<rss")) {
            return List.of();
        }
        return parseRss(xml, limit);
    }

    private List<NewsArticle> fetchGuardian(String stateName, int limit) {
        if (guardianApiKey == null || guardianApiKey.isBlank()) {
            return List.of();
        }
        String query = URLEncoder.encode("\"" + stateName + "\" senate election US", StandardCharsets.UTF_8);
        String url = guardianBaseUrl
                + "?q=" + query
                + "&section=us-news"
                + "&order-by=newest"
                + "&page-size=" + Math.max(1, Math.min(50, limit))
                + "&api-key=" + URLEncoder.encode(guardianApiKey, StandardCharsets.UTF_8);
        JsonNode node = restClient.get().uri(url).retrieve().body(JsonNode.class);
        if (node == null) {
            return List.of();
        }
        JsonNode results = node.path("response").path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<NewsArticle> out = new ArrayList<>();
        List<NewsArticle> broadFallback = new ArrayList<>();
        for (JsonNode item : results) {
            String title = item.path("webTitle").asText("");
            String link = item.path("webUrl").asText("");
            String titleLower = title.toLowerCase(Locale.ROOT);
            boolean relevant = titleLower.contains(stateName.toLowerCase(Locale.ROOT))
                    || titleLower.contains("senate")
                    || titleLower.contains("midterm")
                    || titleLower.contains("election");
            if (!title.isBlank() && !link.isBlank()) {
                NewsArticle article = new NewsArticle(title, link, "The Guardian");
                broadFallback.add(article);
                if (relevant) {
                    out.add(article);
                }
            }
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.isEmpty()) {
            return broadFallback.stream().limit(limit).toList();
        }
        return out;
    }

    private List<NewsArticle> fetchAssociatedPress(String stateName, int limit) {
        List<NewsArticle> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Try AP feeds directly (if available in this environment).
        for (String feedUrl : apFeedUrls) {
            if (feedUrl == null || feedUrl.isBlank()) {
                continue;
            }
            try {
                String xml = restClient.get()
                        .uri(feedUrl)
                        .header("User-Agent", "Mozilla/5.0 (compatible; CW3-MidTerm-Tracker/1.0)")
                        .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                        .retrieve()
                        .body(String.class);
                if (xml == null || xml.isBlank() || !xml.toLowerCase(Locale.ROOT).contains("<rss")) {
                    continue;
                }
                List<NewsArticle> parsed = parseRss(xml, limit);
                List<NewsArticle> filtered = parsed.stream()
                        .filter(a -> {
                            String text = (a.title() + " " + a.url()).toLowerCase(Locale.ROOT);
                            return text.contains(stateName.toLowerCase(Locale.ROOT))
                                    || text.contains("senate")
                                    || text.contains("election");
                        })
                        .map(a -> new NewsArticle(a.title(), a.url(), "Associated Press"))
                        .toList();
                mergeUnique(out, seen, filtered, limit);
                if (out.size() >= limit) {
                    return out;
                }
            } catch (Exception ignored) {
                // Keep moving through provider chain.
            }
        }

        // Fallback AP coverage via Google domain query.
        String apQuery = "\"" + stateName + "\" senate election site:apnews.com";
        mergeUnique(out, seen, fetchGoogleByQuery(apQuery, limit).stream()
                .map(a -> new NewsArticle(a.title(), a.url(), "Associated Press (via Google News)"))
                .toList(), limit);
        return out;
    }

    private void mergeUnique(List<NewsArticle> target, Set<String> seen, List<NewsArticle> candidate, int limit) {
        for (NewsArticle article : candidate) {
            String key = ((article.url() == null ? "" : article.url()) + "|" +
                    (article.title() == null ? "" : article.title())).toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                target.add(article);
            }
            if (target.size() >= limit) {
                return;
            }
        }
    }

    private List<NewsArticle> parseRss(String xml, int limit) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var itemNodes = document.getElementsByTagName("item");

            List<NewsArticle> articles = new ArrayList<>();
            for (int i = 0; i < itemNodes.getLength() && articles.size() < limit; i++) {
                var node = itemNodes.item(i);
                var children = node.getChildNodes();
                String title = "";
                String link = "";
                String source = "Google News";

                for (int c = 0; c < children.getLength(); c++) {
                    var child = children.item(c);
                    String name = child.getNodeName();
                    String text = child.getTextContent() == null ? "" : child.getTextContent().trim();
                    if ("title".equalsIgnoreCase(name)) {
                        title = text;
                    } else if ("link".equalsIgnoreCase(name)) {
                        link = text;
                    } else if ("source".equalsIgnoreCase(name) && !text.isBlank()) {
                        source = text;
                    }
                }

                if (!title.isBlank() && !link.isBlank()) {
                    articles.add(new NewsArticle(title, link, source));
                }
            }
            return articles;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Google News RSS XML", ex);
        }
    }
}
