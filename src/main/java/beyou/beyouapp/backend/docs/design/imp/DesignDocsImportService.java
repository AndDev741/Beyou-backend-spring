package beyou.beyouapp.backend.docs.design.imp;

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

import beyou.beyouapp.backend.docs.design.DesignTopicRepository;
import beyou.beyouapp.backend.docs.design.dto.DesignDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.design.dto.DesignDocsImportResultDTO;
import beyou.beyouapp.backend.docs.design.entity.DesignTopic;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicContent;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicStatus;
import beyou.beyouapp.backend.docs.design.imp.DesignDocsImportParser.DesignTopicMetadata;
import beyou.beyouapp.backend.docs.design.imp.DesignDocsImportParser.MarkdownContent;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DesignDocsImportService {
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_PATH = "design";
    private static final String BASE_API_URL = "https://api.github.com/repos";

    private final DesignTopicRepository topicRepository;
    private final DesignDocsImportParser parser;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${docs.import.repoOwner:}")
    private String repoOwner;

    @Value("${docs.import.repoName:}")
    private String repoName;

    @Value("${docs.import.branch:main}")
    private String repoBranch;

    @Value("${docs.import.design.path:design}")
    private String repoPath;

    @Value("${docs.import.token:}")
    private String repoToken;

    @Transactional
    public DesignDocsImportResultDTO importFromGitHub(DesignDocsImportRequestDTO request) {
        ImportSource source = resolveSource(request);
        validateSource(source);

        List<DesignDocsImportTopic> topics = fetchTopicsFromGitHub(source);
        return applyImport(topics);
    }

    DesignDocsImportResultDTO applyImport(List<DesignDocsImportTopic> topics) {
        Map<String, DesignDocsImportTopic> topicMap = new HashMap<>();
        for (DesignDocsImportTopic topic : topics) {
            topicMap.put(topic.key(), topic);
        }

        int importedCount = 0;
        for (DesignDocsImportTopic importTopic : topics) {
            upsertTopic(importTopic);
            importedCount++;
        }

        int archivedCount = archiveMissingTopics(topicMap.keySet());

        return new DesignDocsImportResultDTO(importedCount, archivedCount);
    }

    private void upsertTopic(DesignDocsImportTopic importTopic) {
        DesignTopic topic = topicRepository.findByKey(importTopic.key())
            .orElseGet(() -> {
                DesignTopic newTopic = new DesignTopic();
                newTopic.setKey(importTopic.key());
                return newTopic;
            });

        topic.setOrderIndex(importTopic.orderIndex());
        topic.setStatus(importTopic.status());

        List<DesignTopicContent> contents = topic.getContents();
        if (contents == null) {
            contents = new ArrayList<>();
            topic.setContents(contents);
        }

        Map<String, DesignTopicContent> existingByLocale = new HashMap<>();
        for (DesignTopicContent content : contents) {
            String locale = normalizeLocale(content.getLocale());
            if (locale != null && !locale.isBlank()) {
                existingByLocale.putIfAbsent(locale, content);
            }
        }

        Set<String> nextLocales = new HashSet<>();
        for (DesignDocsImportContent content : importTopic.contents()) {
            String locale = normalizeLocale(content.locale());
            if (locale == null || locale.isBlank()) {
                continue;
            }

            nextLocales.add(locale);

            DesignTopicContent topicContent = existingByLocale.get(locale);
            if (topicContent == null) {
                topicContent = new DesignTopicContent();
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

    private int archiveMissingTopics(Set<String> activeKeys) {
        List<DesignTopic> topics = topicRepository.findAll();
        int archivedCount = 0;
        for (DesignTopic topic : topics) {
            if (!activeKeys.contains(topic.getKey()) && topic.getStatus() != DesignTopicStatus.ARCHIVED) {
                topic.setStatus(DesignTopicStatus.ARCHIVED);
                topicRepository.save(topic);
                archivedCount++;
            }
        }

        return archivedCount;
    }

    private ImportSource resolveSource(DesignDocsImportRequestDTO request) {
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

    private List<DesignDocsImportTopic> fetchTopicsFromGitHub(ImportSource source) {
        List<GitHubContentItem> directories = fetchDirectory(source, source.path());
        List<DesignDocsImportTopic> topics = new ArrayList<>();

        for (GitHubContentItem item : directories) {
            if (!"dir".equalsIgnoreCase(item.type())) {
                continue;
            }

            topics.add(fetchTopicFromDirectory(source, item));
        }

        if (topics.isEmpty()) {
            log.warn("No design topics found in repo path: {}", source.path());
        }

        return topics;
    }

    private DesignDocsImportTopic fetchTopicFromDirectory(ImportSource source, GitHubContentItem directory) {
        List<GitHubContentItem> files = fetchDirectory(source, directory.path());
        GitHubContentItem topicYaml = findFile(files, "topic.yaml");

        if (topicYaml == null) {
            throw new DocsImportFailed("Missing topic.yaml in " + directory.path());
        }

        String topicYamlContent = fetchFileContent(source, topicYaml.path());

        DesignTopicMetadata metadata = parser.parseTopicYaml(topicYamlContent, directory.name());

        List<DesignDocsImportContent> contents = new ArrayList<>();
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

            contents.add(new DesignDocsImportContent(
                locale,
                title,
                parsed.summary(),
                parsed.body()
            ));
        }

        if (contents.isEmpty()) {
            throw new DocsImportFailed("No markdown locale files found in " + directory.path());
        }

        return new DesignDocsImportTopic(
            metadata.key(),
            metadata.orderIndex(),
            metadata.status(),
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

    record DesignDocsImportTopic(
        String key,
        int orderIndex,
        DesignTopicStatus status,
        List<DesignDocsImportContent> contents
    ) {}

    record DesignDocsImportContent(
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
