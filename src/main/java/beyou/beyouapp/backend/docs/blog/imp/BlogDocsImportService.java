package beyou.beyouapp.backend.docs.blog.imp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.docs.blog.BlogTopicRepository;
import beyou.beyouapp.backend.docs.blog.dto.BlogDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.blog.dto.BlogDocsImportResultDTO;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopic;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicCategory;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicContent;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicStatus;
import beyou.beyouapp.backend.docs.blog.imp.BlogDocsImportParser.BlogTopicMetadata;
import beyou.beyouapp.backend.docs.blog.imp.BlogDocsImportParser.MarkdownContent;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlogDocsImportService {
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_PATH = "blog";
    private static final String BASE_API_URL = "https://api.github.com/repos";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BlogTopicRepository topicRepository;
    private final BlogDocsImportParser parser;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${docs.import.repoOwner:}")
    private String repoOwner;

    @Value("${docs.import.repoName:}")
    private String repoName;

    @Value("${docs.import.branch:main}")
    private String repoBranch;

    @Value("${docs.import.blog.path:blog}")
    private String repoPath;

    @Value("${docs.import.token:}")
    private String repoToken;

    @Transactional
    public BlogDocsImportResultDTO importFromGitHub(BlogDocsImportRequestDTO request) {
        ImportSource source = resolveSource(request);
        validateSource(source);

        List<BlogDocsImportTopic> topics = fetchTopicsFromGitHub(source);
        return applyImport(topics);
    }

    BlogDocsImportResultDTO applyImport(List<BlogDocsImportTopic> topics) {
        Map<String, BlogDocsImportTopic> topicMap = new HashMap<>();
        for (BlogDocsImportTopic topic : topics) {
            topicMap.put(topic.key(), topic);
        }

        int importedCount = 0;
        for (BlogDocsImportTopic importTopic : topics) {
            upsertTopic(importTopic);
            importedCount++;
        }

        int archivedCount = archiveMissingTopics(topicMap.keySet());

        return new BlogDocsImportResultDTO(importedCount, archivedCount);
    }

    private void upsertTopic(BlogDocsImportTopic importTopic) {
        BlogTopic topic = topicRepository.findByKey(importTopic.key())
            .orElseGet(() -> {
                BlogTopic newTopic = new BlogTopic();
                newTopic.setKey(importTopic.key());
                return newTopic;
            });

        topic.setStatus(importTopic.status());
        topic.setCategory(importTopic.category());
        topic.setTags(parseTagsList(importTopic.tags()));
        topic.setFeatured(importTopic.featured());
        topic.setPublishedAt(importTopic.publishedAt());
        topic.setCoverColor(importTopic.coverColor());
        topic.setCoverEmoji(importTopic.coverEmoji());
        topic.setAuthor(importTopic.author());

        List<BlogTopicContent> contents = topic.getContents();
        if (contents == null) {
            contents = new ArrayList<>();
            topic.setContents(contents);
        }

        Map<String, BlogTopicContent> existingByLocale = new HashMap<>();
        for (BlogTopicContent content : contents) {
            String locale = normalizeLocale(content.getLocale());
            if (locale != null && !locale.isBlank()) {
                existingByLocale.putIfAbsent(locale, content);
            }
        }

        Set<String> nextLocales = new HashSet<>();
        for (BlogDocsImportContent content : importTopic.contents()) {
            String locale = normalizeLocale(content.locale());
            if (locale == null || locale.isBlank()) {
                continue;
            }

            nextLocales.add(locale);

            BlogTopicContent topicContent = existingByLocale.get(locale);
            if (topicContent == null) {
                topicContent = new BlogTopicContent();
                topicContent.setTopic(topic);
                topicContent.setLocale(locale);
                contents.add(topicContent);
            }

            topicContent.setTitle(content.title());
            topicContent.setSummary(content.summary());
            topicContent.setDocMarkdown(content.docMarkdown());
        }

        contents.removeIf(content -> {
            String locale = normalizeLocale(content.getLocale());
            return locale == null || !nextLocales.contains(locale);
        });

        topicRepository.save(topic);
    }

    private List<String> parseTagsList(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private int archiveMissingTopics(Set<String> activeKeys) {
        List<BlogTopic> topics = topicRepository.findAll();
        int archivedCount = 0;
        for (BlogTopic topic : topics) {
            if (!activeKeys.contains(topic.getKey()) && topic.getStatus() != BlogTopicStatus.ARCHIVED) {
                topic.setStatus(BlogTopicStatus.ARCHIVED);
                topicRepository.save(topic);
                archivedCount++;
            }
        }

        return archivedCount;
    }

    private ImportSource resolveSource(BlogDocsImportRequestDTO request) {
        String owner = valueOrFallback(request == null ? null : request.repoOwner(), repoOwner);
        String name = valueOrFallback(request == null ? null : request.repoName(), repoName);
        String branch = valueOrFallback(request == null ? null : request.branch(), repoBranch);
        String path = valueOrFallback(request == null ? null : request.path(), repoPath);
        String token = valueOrFallback(request == null ? null : request.token(), repoToken);

        if (branch == null || branch.isBlank()) {
            branch = DEFAULT_BRANCH;
        }

        if (path == null || path.isBlank()) {
            path = DEFAULT_PATH;
        }

        return new ImportSource(owner, name, branch, path, token);
    }

    private void validateSource(ImportSource source) {
        if (source.owner() == null || source.owner().isBlank()) {
            throw new DocsImportFailed("Missing docs.import.repoOwner");
        }
        if (source.name() == null || source.name().isBlank()) {
            throw new DocsImportFailed("Missing docs.import.repoName");
        }
    }

    private List<BlogDocsImportTopic> fetchTopicsFromGitHub(ImportSource source) {
        List<GitHubContentItem> directories = fetchDirectory(source, source.path());
        List<BlogDocsImportTopic> topics = new ArrayList<>();

        for (GitHubContentItem item : directories) {
            if (!"dir".equalsIgnoreCase(item.type())) {
                continue;
            }

            topics.add(fetchTopicFromDirectory(source, item));
        }

        if (topics.isEmpty()) {
            log.warn("No blog topics found in repo path: {}", source.path());
        }

        return topics;
    }

    private BlogDocsImportTopic fetchTopicFromDirectory(ImportSource source, GitHubContentItem directory) {
        List<GitHubContentItem> files = fetchDirectory(source, directory.path());
        GitHubContentItem topicYaml = findFile(files, "topic.yaml");

        if (topicYaml == null) {
            throw new DocsImportFailed("Missing topic.yaml in " + directory.path());
        }

        String topicYamlContent = fetchFileContent(source, topicYaml.path());

        BlogTopicMetadata metadata = parser.parseTopicYaml(topicYamlContent, directory.name());

        List<BlogDocsImportContent> contents = new ArrayList<>();
        for (GitHubContentItem file : files) {
            if (!"file".equalsIgnoreCase(file.type()) || !file.name().endsWith(".md")) {
                continue;
            }
            String locale = normalizeLocale(file.name().replace(".md", ""));
            if (locale.isBlank()) {
                continue;
            }

            String markdown = fetchFileContent(source, file.path());
            MarkdownContent parsed = parser.parseMarkdown(markdown);

            String title = parsed.title() != null ? parsed.title() : metadata.key();

            contents.add(new BlogDocsImportContent(
                locale,
                title,
                parsed.summary(),
                parsed.body()
            ));
        }

        if (contents.isEmpty()) {
            throw new DocsImportFailed("No markdown locale files found in " + directory.path());
        }

        return new BlogDocsImportTopic(
            metadata.key(),
            metadata.status(),
            metadata.category(),
            metadata.tags(),
            metadata.featured(),
            metadata.publishedAt(),
            metadata.coverColor(),
            metadata.coverEmoji(),
            metadata.author(),
            contents
        );
    }

    private GitHubContentItem findFile(List<GitHubContentItem> files, String filename) {
        return files.stream()
            .filter(file -> filename.equalsIgnoreCase(file.name()))
            .findFirst()
            .orElse(null);
    }

    private List<GitHubContentItem> fetchDirectory(ImportSource source, String path) {
        String url = buildContentsUrl(source, path);
        HttpEntity<Void> request = new HttpEntity<>(buildHeaders(source.token()));

        ResponseEntity<GitHubContentItem[]> response = restTemplate.exchange(url, HttpMethod.GET, request, GitHubContentItem[].class);
        GitHubContentItem[] body = response.getBody();
        if (body == null) {
            return List.of();
        }

        return List.of(body);
    }

    private String fetchFileContent(ImportSource source, String path) {
        String url = buildContentsUrl(source, path);
        HttpEntity<Void> request = new HttpEntity<>(buildHeaders(source.token()));

        ResponseEntity<GitHubContentItem> response = restTemplate.exchange(url, HttpMethod.GET, request, GitHubContentItem.class);
        GitHubContentItem body = response.getBody();
        if (body == null || body.content() == null) {
            throw new DocsImportFailed("Missing file content at " + path);
        }

        if (body.encoding() != null && !"base64".equalsIgnoreCase(body.encoding())) {
            throw new DocsImportFailed("Unsupported encoding for " + path + ": " + body.encoding());
        }

        byte[] decoded = Base64.getMimeDecoder().decode(body.content());
        return new String(decoded, StandardCharsets.UTF_8).trim();
    }

    private HttpHeaders buildHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("User-Agent", "Beyou-Docs-Importer");
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private String buildContentsUrl(ImportSource source, String path) {
        String safePath = path.startsWith("/") ? path.substring(1) : path;
        return String.format("%s/%s/%s/contents/%s?ref=%s", BASE_API_URL, source.owner(), source.name(), safePath, source.branch());
    }

    private String valueOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String normalizeLocale(String locale) {
        if (locale == null) {
            return null;
        }
        return locale.trim().toLowerCase();
    }

    record ImportSource(String owner, String name, String branch, String path, String token) {}

    record BlogDocsImportTopic(
        String key,
        BlogTopicStatus status,
        BlogTopicCategory category,
        String tags,
        boolean featured,
        java.sql.Date publishedAt,
        String coverColor,
        String coverEmoji,
        String author,
        List<BlogDocsImportContent> contents
    ) {}

    record BlogDocsImportContent(
        String locale,
        String title,
        String summary,
        String docMarkdown
    ) {}

    record GitHubContentItem(
        String name,
        String path,
        String type,
        String encoding,
        String content
    ) {}
}
