package beyou.beyouapp.backend.docs.architecture.imp;

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

import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicRepository;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportResultDTO;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopic;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicContent;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportParser.ArchitectureTopicMetadata;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportParser.MarkdownContent;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureDocsImportService {
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_PATH = "architecture";
    private static final String BASE_API_URL = "https://api.github.com/repos";

    private final ArchitectureTopicRepository topicRepository;
    private final ArchitectureDocsImportParser parser;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${docs.import.repoOwner:}")
    private String repoOwner;

    @Value("${docs.import.repoName:}")
    private String repoName;

    @Value("${docs.import.branch:main}")
    private String repoBranch;

    @Value("${docs.import.path:architecture}")
    private String repoPath;

    @Value("${docs.import.token:}")
    private String repoToken;

    @Transactional
    public ArchitectureDocsImportResultDTO importFromGitHub(ArchitectureDocsImportRequestDTO request) {
        ImportSource source = resolveSource(request);
        validateSource(source);

        List<ArchitectureDocsImportTopic> topics = fetchTopicsFromGitHub(source);
        return applyImport(topics);
    }

    ArchitectureDocsImportResultDTO applyImport(List<ArchitectureDocsImportTopic> topics) {
        Map<String, ArchitectureDocsImportTopic> topicMap = new HashMap<>();
        for (ArchitectureDocsImportTopic topic : topics) {
            topicMap.put(topic.key(), topic);
        }

        int importedCount = 0;
        for (ArchitectureDocsImportTopic importTopic : topics) {
            upsertTopic(importTopic);
            importedCount++;
        }

        int archivedCount = archiveMissingTopics(topicMap.keySet());

        return new ArchitectureDocsImportResultDTO(importedCount, archivedCount);
    }

    private void upsertTopic(ArchitectureDocsImportTopic importTopic) {
        ArchitectureTopic topic = topicRepository.findByKey(importTopic.key())
            .orElseGet(() -> {
                ArchitectureTopic newTopic = new ArchitectureTopic();
                newTopic.setKey(importTopic.key());
                return newTopic;
            });

        topic.setOrderIndex(importTopic.orderIndex());
        topic.setStatus(importTopic.status());

        List<ArchitectureTopicContent> contents = topic.getContents();
        if (contents == null) {
            contents = new ArrayList<>();
            topic.setContents(contents);
        }

        Map<String, ArchitectureTopicContent> existingByLocale = new HashMap<>();
        for (ArchitectureTopicContent content : contents) {
            String locale = normalizeLocale(content.getLocale());
            if (locale != null && !locale.isBlank()) {
                existingByLocale.putIfAbsent(locale, content);
            }
        }

        Set<String> nextLocales = new HashSet<>();
        for (ArchitectureDocsImportContent content : importTopic.contents()) {
            String locale = normalizeLocale(content.locale());
            if (locale == null || locale.isBlank()) {
                continue;
            }

            nextLocales.add(locale);

            ArchitectureTopicContent topicContent = existingByLocale.get(locale);
            if (topicContent == null) {
                topicContent = new ArchitectureTopicContent();
                topicContent.setTopic(topic);
                topicContent.setLocale(locale);
                contents.add(topicContent);
            }

            topicContent.setTitle(content.title());
            topicContent.setSummary(content.summary());
            topicContent.setDiagramMermaid(importTopic.diagramMermaid());
            topicContent.setDocMarkdown(content.docMarkdown());
        }

        contents.removeIf(content -> {
            String locale = normalizeLocale(content.getLocale());
            return locale == null || !nextLocales.contains(locale);
        });

        topicRepository.save(topic);
    }

    private int archiveMissingTopics(Set<String> importedKeys) {
        List<ArchitectureTopic> existingTopics = topicRepository.findAll();
        int archivedCount = 0;

        for (ArchitectureTopic topic : existingTopics) {
            if (!importedKeys.contains(topic.getKey()) && topic.getStatus() != ArchitectureTopicStatus.ARCHIVED) {
                topic.setStatus(ArchitectureTopicStatus.ARCHIVED);
                archivedCount++;
            }
        }

        return archivedCount;
    }

    private ImportSource resolveSource(ArchitectureDocsImportRequestDTO request) {
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

    private List<ArchitectureDocsImportTopic> fetchTopicsFromGitHub(ImportSource source) {
        List<GitHubContentItem> directories = fetchDirectory(source, source.path());
        List<ArchitectureDocsImportTopic> topics = new ArrayList<>();

        for (GitHubContentItem item : directories) {
            if (!"dir".equalsIgnoreCase(item.type())) {
                continue;
            }

            topics.add(fetchTopicFromDirectory(source, item));
        }

        if (topics.isEmpty()) {
            log.warn("No architecture topics found in repo path: {}", source.path());
        }

        return topics;
    }

    private ArchitectureDocsImportTopic fetchTopicFromDirectory(ImportSource source, GitHubContentItem directory) {
        List<GitHubContentItem> files = fetchDirectory(source, directory.path());
        GitHubContentItem topicYaml = findFile(files, "topic.yaml");
        GitHubContentItem diagramFile = findFile(files, "diagram.mmd");

        if (topicYaml == null) {
            throw new DocsImportFailed("Missing topic.yaml in " + directory.path());
        }
        if (diagramFile == null) {
            throw new DocsImportFailed("Missing diagram.mmd in " + directory.path());
        }

        String topicYamlContent = fetchFileContent(source, topicYaml.path());
        String diagramContent = fetchFileContent(source, diagramFile.path());

        ArchitectureTopicMetadata metadata = parser.parseTopicYaml(topicYamlContent, directory.name());

        List<ArchitectureDocsImportContent> contents = new ArrayList<>();
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

            contents.add(new ArchitectureDocsImportContent(
                locale,
                title,
                parsed.summary(),
                parsed.body()
            ));
        }

        if (contents.isEmpty()) {
            throw new DocsImportFailed("No markdown locale files found in " + directory.path());
        }

        return new ArchitectureDocsImportTopic(
            metadata.key(),
            metadata.orderIndex(),
            metadata.status(),
            diagramContent,
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

    record ArchitectureDocsImportTopic(
        String key,
        int orderIndex,
        ArchitectureTopicStatus status,
        String diagramMermaid,
        List<ArchitectureDocsImportContent> contents
    ) {}

    record ArchitectureDocsImportContent(
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
