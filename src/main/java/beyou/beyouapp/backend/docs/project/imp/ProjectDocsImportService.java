package beyou.beyouapp.backend.docs.project.imp;

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

import beyou.beyouapp.backend.docs.project.ProjectTopicRepository;
import beyou.beyouapp.backend.docs.project.dto.ProjectDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.project.dto.ProjectDocsImportResultDTO;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopic;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicContent;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicStatus;
import beyou.beyouapp.backend.docs.project.imp.ProjectDocsImportParser.MarkdownContent;
import beyou.beyouapp.backend.docs.project.imp.ProjectDocsImportParser.ProjectTopicMetadata;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDocsImportService {
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_PATH = "projects";
    private static final String BASE_API_URL = "https://api.github.com/repos";

    private final ProjectTopicRepository topicRepository;
    private final ProjectDocsImportParser parser;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${docs.import.repoOwner:}")
    private String repoOwner;

    @Value("${docs.import.repoName:}")
    private String repoName;

    @Value("${docs.import.branch:main}")
    private String repoBranch;

    @Value("${docs.import.projects.path:projects}")
    private String repoPath;

    @Value("${docs.import.token:}")
    private String repoToken;

    @Transactional
    public ProjectDocsImportResultDTO importFromGitHub(ProjectDocsImportRequestDTO request) {
        ImportSource source = resolveSource(request);
        validateSource(source);

        List<ProjectDocsImportTopic> topics = fetchTopicsFromGitHub(source);
        return applyImport(topics);
    }

    ProjectDocsImportResultDTO applyImport(List<ProjectDocsImportTopic> topics) {
        Map<String, ProjectDocsImportTopic> topicMap = new HashMap<>();
        for (ProjectDocsImportTopic topic : topics) {
            topicMap.put(topic.key(), topic);
        }

        int importedCount = 0;
        for (ProjectDocsImportTopic importTopic : topics) {
            upsertTopic(importTopic);
            importedCount++;
        }

        int archivedCount = archiveMissingTopics(topicMap.keySet());

        return new ProjectDocsImportResultDTO(importedCount, archivedCount);
    }

    private void upsertTopic(ProjectDocsImportTopic importTopic) {
        ProjectTopic topic = topicRepository.findByKey(importTopic.key())
            .orElseGet(() -> {
                ProjectTopic newTopic = new ProjectTopic();
                newTopic.setKey(importTopic.key());
                return newTopic;
            });

        topic.setOrderIndex(importTopic.orderIndex());
        topic.setStatus(importTopic.status());

        List<ProjectTopicContent> contents = topic.getContents();
        if (contents == null) {
            contents = new ArrayList<>();
            topic.setContents(contents);
        }

        Map<String, ProjectTopicContent> existingByLocale = new HashMap<>();
        for (ProjectTopicContent content : contents) {
            String locale = normalizeLocale(content.getLocale());
            if (locale != null && !locale.isBlank()) {
                existingByLocale.putIfAbsent(locale, content);
            }
        }

        Set<String> nextLocales = new HashSet<>();
        for (ProjectDocsImportContent content : importTopic.contents()) {
            String locale = normalizeLocale(content.locale());
            if (locale == null || locale.isBlank()) {
                continue;
            }

            nextLocales.add(locale);

            ProjectTopicContent topicContent = existingByLocale.get(locale);
            if (topicContent == null) {
                topicContent = new ProjectTopicContent();
                topicContent.setTopic(topic);
                topicContent.setLocale(locale);
                contents.add(topicContent);
            }

            topicContent.setTitle(content.title());
            topicContent.setSummary(content.summary());
            topicContent.setDiagramMermaid(importTopic.diagramMermaid());
            topicContent.setDocMarkdown(content.docMarkdown());
            topicContent.setDesignTopicKey(importTopic.designTopicKey());
            topicContent.setArchitectureTopicKey(importTopic.architectureTopicKey());
            topicContent.setRepositoryUrl(importTopic.repositoryUrl());
            topicContent.setTags(convertTagsToJson(importTopic.tags()));
        }

        contents.removeIf(content -> {
            String locale = normalizeLocale(content.getLocale());
            return locale == null || !nextLocales.contains(locale);
        });

        topicRepository.save(topic);
    }

    private int archiveMissingTopics(Set<String> importedKeys) {
        List<ProjectTopic> existingTopics = topicRepository.findAll();
        int archivedCount = 0;

        for (ProjectTopic topic : existingTopics) {
            if (!importedKeys.contains(topic.getKey()) && topic.getStatus() != ProjectTopicStatus.ARCHIVED) {
                topic.setStatus(ProjectTopicStatus.ARCHIVED);
                archivedCount++;
            }
        }

        return archivedCount;
    }

    private ImportSource resolveSource(ProjectDocsImportRequestDTO request) {
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

    private List<ProjectDocsImportTopic> fetchTopicsFromGitHub(ImportSource source) {
        List<GitHubContentItem> directories = fetchDirectory(source, source.path());
        List<ProjectDocsImportTopic> topics = new ArrayList<>();

        for (GitHubContentItem item : directories) {
            if (!"dir".equalsIgnoreCase(item.type())) {
                continue;
            }

            topics.add(fetchTopicFromDirectory(source, item));
        }

        if (topics.isEmpty()) {
            log.warn("No project topics found in repo path: {}", source.path());
        }

        return topics;
    }

    private ProjectDocsImportTopic fetchTopicFromDirectory(ImportSource source, GitHubContentItem directory) {
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

        ProjectTopicMetadata metadata = parser.parseTopicYaml(topicYamlContent, directory.name());

        List<ProjectDocsImportContent> contents = new ArrayList<>();
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

            contents.add(new ProjectDocsImportContent(
                locale,
                title,
                parsed.summary(),
                parsed.body()
            ));
        }

        if (contents.isEmpty()) {
            throw new DocsImportFailed("No markdown locale files found in " + directory.path());
        }

        return new ProjectDocsImportTopic(
            metadata.key(),
            metadata.orderIndex(),
            metadata.status(),
            diagramContent,
            metadata.designTopicKey(),
            metadata.architectureTopicKey(),
            metadata.repositoryUrl(),
            metadata.tags(),
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

    private String convertTagsToJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        // Simple JSON array serialization
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(tags.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record ImportSource(String owner, String name, String branch, String path, String token) {}

    record ProjectDocsImportTopic(
        String key,
        int orderIndex,
        ProjectTopicStatus status,
        String diagramMermaid,
        String designTopicKey,
        String architectureTopicKey,
        String repositoryUrl,
        List<String> tags,
        List<ProjectDocsImportContent> contents
    ) {}

    record ProjectDocsImportContent(
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