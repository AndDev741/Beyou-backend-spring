package beyou.beyouapp.backend.docs.api.imp;

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

import beyou.beyouapp.backend.docs.api.ApiControllerTopicRepository;
import beyou.beyouapp.backend.docs.api.dto.ApiDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.api.dto.ApiDocsImportResultDTO;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerTopic;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerContent;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiDocsImportService {
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_PATH = "api";
    private static final String BASE_API_URL = "https://api.github.com/repos";

    private final ApiControllerTopicRepository topicRepository;
    private final ApiDocsImportParser parser;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${docs.import.repoOwner:}")
    private String repoOwner;

    @Value("${docs.import.repoName:}")
    private String repoName;

    @Value("${docs.import.branch:main}")
    private String repoBranch;

    @Value("${docs.import.api.path:api}")
    private String repoPath;

    @Value("${docs.import.token:}")
    private String repoToken;

    @Transactional
    public ApiDocsImportResultDTO importFromGitHub(ApiDocsImportRequestDTO request) {
        ImportSource source = resolveSource(request);
        validateSource(source);

        List<ApiDocsImportTopic> topics = fetchTopicsFromGitHub(source);
        return applyImport(topics);
    }

    ApiDocsImportResultDTO applyImport(List<ApiDocsImportTopic> topics) {
        Map<String, ApiDocsImportTopic> topicMap = new HashMap<>();
        for (ApiDocsImportTopic topic : topics) {
            topicMap.put(topic.key(), topic);
        }

        int importedCount = 0;
        for (ApiDocsImportTopic importTopic : topics) {
            upsertTopic(importTopic);
            importedCount++;
        }

        int archivedCount = archiveMissingTopics(topicMap.keySet());

        return new ApiDocsImportResultDTO(importedCount, archivedCount);
    }

    private void upsertTopic(ApiDocsImportTopic importTopic) {
        ApiControllerTopic topic = topicRepository.findByKey(importTopic.key())
            .orElseGet(() -> {
                ApiControllerTopic newTopic = new ApiControllerTopic();
                newTopic.setKey(importTopic.key());
                return newTopic;
            });

        topic.setOrderIndex(importTopic.orderIndex());
        topic.setStatus(importTopic.status());

        List<ApiControllerContent> contents = topic.getContents();
        if (contents == null) {
            contents = new ArrayList<>();
            topic.setContents(contents);
        }

        Map<String, ApiControllerContent> existingByLocale = new HashMap<>();
        for (ApiControllerContent content : contents) {
            String locale = normalizeLocale(content.getLocale());
            if (locale != null && !locale.isBlank()) {
                existingByLocale.putIfAbsent(locale, content);
            }
        }

        Set<String> nextLocales = new HashSet<>();
        for (ApiDocsImportContent content : importTopic.contents()) {
            String locale = normalizeLocale(content.locale());
            if (locale == null || locale.isBlank()) {
                continue;
            }

            nextLocales.add(locale);

            ApiControllerContent topicContent = existingByLocale.get(locale);
            if (topicContent == null) {
                topicContent = new ApiControllerContent();
                topicContent.setTopic(topic);
                topicContent.setLocale(locale);
                contents.add(topicContent);
            }

            topicContent.setTitle(content.title());
            topicContent.setSummary(content.summary());
            topicContent.setApiCatalog(content.apiCatalog());
        }

        contents.removeIf(content -> {
            String locale = normalizeLocale(content.getLocale());
            return locale == null || !nextLocales.contains(locale);
        });

        topicRepository.save(topic);
    }

    private int archiveMissingTopics(Set<String> importedKeys) {
        List<ApiControllerTopic> existingTopics = topicRepository.findAll();
        int archivedCount = 0;

        for (ApiControllerTopic topic : existingTopics) {
            if (!importedKeys.contains(topic.getKey()) && topic.getStatus() != ApiControllerStatus.ARCHIVED) {
                topic.setStatus(ApiControllerStatus.ARCHIVED);
                archivedCount++;
            }
        }

        return archivedCount;
    }

    private ImportSource resolveSource(ApiDocsImportRequestDTO request) {
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

    private List<ApiDocsImportTopic> fetchTopicsFromGitHub(ImportSource source) {
        List<GitHubContentItem> directories = fetchDirectory(source, source.path());
        List<ApiDocsImportTopic> topics = new ArrayList<>();

        for (GitHubContentItem item : directories) {
            if (!"dir".equalsIgnoreCase(item.type())) {
                continue;
            }

            topics.add(fetchTopicFromDirectory(source, item));
        }

        if (topics.isEmpty()) {
            log.warn("No API controller topics found in repo path: {}", source.path());
        }

        return topics;
    }

    private ApiDocsImportTopic fetchTopicFromDirectory(ImportSource source, GitHubContentItem directory) {
        List<GitHubContentItem> files = fetchDirectory(source, directory.path());
        GitHubContentItem controllerYaml = findFile(files, "controller.yaml");
        GitHubContentItem openapiYaml = findFile(files, "openapi.yaml");

        if (controllerYaml == null) {
            throw new DocsImportFailed("Missing controller.yaml in " + directory.path());
        }
        if (openapiYaml == null) {
            throw new DocsImportFailed("Missing openapi.yaml in " + directory.path());
        }

        String controllerYamlContent = fetchFileContent(source, controllerYaml.path());
        String openapiContent = fetchFileContent(source, openapiYaml.path());

        var metadata = parser.parseControllerYaml(controllerYamlContent, directory.name());
        var openApi = parser.parseOpenApi(openapiContent);

        List<ApiDocsImportContent> contents = new ArrayList<>();
        for (GitHubContentItem file : files) {
            if (!"file".equalsIgnoreCase(file.type()) || !file.name().endsWith(".md")) {
                continue;
            }
            String locale = normalizeLocale(file.name().replace(".md", ""));
            if (locale.isBlank()) {
                continue;
            }

            String markdown = fetchFileContent(source, file.path());
            var parsed = parser.parseMarkdown(markdown);

            String title = parsed.title() != null ? parsed.title() : metadata.key();
            String summary = parsed.summary() != null ? parsed.summary() : openApi.summary();

            contents.add(new ApiDocsImportContent(
                locale,
                title,
                summary,
                openApi.spec()
            ));
        }

        if (contents.isEmpty()) {
            throw new DocsImportFailed("No markdown locale files found in " + directory.path());
        }

        return new ApiDocsImportTopic(
            metadata.key(),
            metadata.orderIndex(),
            metadata.status(),
            contents
        );
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

    private GitHubContentItem findFile(List<GitHubContentItem> files, String filename) {
        for (GitHubContentItem file : files) {
            if (filename.equalsIgnoreCase(file.name())) {
                return file;
            }
        }
        return null;
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

    record ApiDocsImportTopic(
        String key,
        int orderIndex,
        ApiControllerStatus status,
        List<ApiDocsImportContent> contents
    ) {}

    record ApiDocsImportContent(
        String locale,
        String title,
        String summary,
        String apiCatalog
    ) {}

    record GitHubContentItem(
        String name,
        String path,
        String type,
        String encoding,
        String content
    ) {}
}