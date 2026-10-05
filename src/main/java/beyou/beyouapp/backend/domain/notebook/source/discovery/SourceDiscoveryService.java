package beyou.beyouapp.backend.domain.notebook.source.discovery;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.source.LinkFetcher;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.source.dto.SourceDTO;
import beyou.beyouapp.backend.domain.notebook.source.discovery.dto.DiscoveredSourceDTO;
import beyou.beyouapp.backend.domain.notebook.source.discovery.dto.DiscoveryResultDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * "Find sources for me": the person describes what they need, a web search finds pages, and
 * the person picks which to add as link sources. Nothing is stored here.
 *
 * <p>Every result is opened before it is offered ({@link LinkFetcher#resolve}): a Gemini result
 * is a Google redirect that has to be followed to the real page, and any result can be dead, a
 * download, or point somewhere private. What is offered is what adding it will fetch, with the
 * same SSRF refusals as adding a link by hand. Results the page already reads as sources are
 * left out. Results are opened in parallel, and the ones still loading after
 * {@link #RESOLVE_BUDGET} are dropped, so a slow site cannot hold the answer.
 */
@Service
@Slf4j
public class SourceDiscoveryService {

    static final Duration RESOLVE_BUDGET = Duration.ofSeconds(20);
    static final int MAX_SUMMARY = 240;

    private final WebSearchClient search;
    private final LinkFetcher fetcher;
    private final NotebookOwnership ownership;
    private final NotebookPageRepository pageRepository;
    private final NotebookSourceService sourceService;
    private final ExecutorService opening = Executors.newVirtualThreadPerTaskExecutor();

    public SourceDiscoveryService(WebSearchClient search, LinkFetcher fetcher, NotebookOwnership ownership,
            NotebookPageRepository pageRepository, NotebookSourceService sourceService) {
        this.search = search;
        this.fetcher = fetcher;
        this.ownership = ownership;
        this.pageRepository = pageRepository;
        this.sourceService = sourceService;
    }

    /** Whether a search provider is configured. The study room hides the feature when it is not. */
    public boolean available() {
        return search.provider() != null;
    }

    public DiscoveryResultDTO discover(User user, UUID pageId, String description) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        if (!available()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_DISCOVERY_UNAVAILABLE, "No search is configured");
        }
        String topic = page.isTopic() ? page.getTitle()
                : pageRepository.findById(page.getTopicId()).map(NotebookPage::getTitle).orElse(page.getTitle());

        List<SearchHit> hits;
        try {
            hits = search.search(query(description, page, topic), prompt(description, page, topic));
        } catch (RuntimeException e) {
            log.warn("Source discovery search failed: {}", e.getMessage());
            throw new BusinessException(ErrorKey.NOTEBOOK_DISCOVERY_FAILED, "The search did not answer");
        }

        // Every source the page already has, switched off or still reading included.
        Set<String> known = sourceService.list(user, pageId).stream()
                .map(SourceDTO::url)
                .filter(u -> u != null && !u.isBlank())
                .map(SourceDiscoveryService::normalise)
                .collect(Collectors.toSet());

        Map<SearchHit, Future<LinkFetcher.Resolved>> opened = new LinkedHashMap<>();
        for (SearchHit hit : hits) {
            opened.put(hit, opening.submit(() -> fetcher.resolve(hit.url())));
        }
        Instant deadline = Instant.now().plus(RESOLVE_BUDGET);
        Map<String, DiscoveredSourceDTO> found = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<SearchHit, Future<LinkFetcher.Resolved>> entry : opened.entrySet()) {
            LinkFetcher.Resolved resolved = await(entry.getValue(), deadline);
            if (resolved == null) {
                skipped++;
                continue;
            }
            String url = resolved.uri().toString();
            String key = normalise(url);
            if (known.contains(key) || found.containsKey(key)) {
                skipped++;
                continue;
            }
            SearchHit hit = entry.getKey();
            String title = firstNonBlank(resolved.title(), hit.title(), resolved.uri().getHost());
            found.put(key, new DiscoveredSourceDTO(title, url, domain(resolved.uri()), summary(hit.snippet())));
        }
        return new DiscoveryResultDTO(search.provider().name(), new ArrayList<>(found.values()), skipped);
    }

    /** Tavily reads a query: the description, with the page it is for when it does not say. */
    static String query(String description, NotebookPage page, String topic) {
        String query = description.strip();
        if (!query.toLowerCase(Locale.ROOT).contains(page.getTitle().toLowerCase(Locale.ROOT))) {
            query = query + " (" + (page.isTopic() ? topic : topic + ": " + page.getTitle()) + ")";
        }
        return query.length() > 400 ? query.substring(0, 400) : query;
    }

    /** Gemini reads instructions; its sources are the pages Google returned for them. */
    static String prompt(String description, NotebookPage page, String topic) {
        StringBuilder prompt = new StringBuilder("Search the web for good sources a learner can study from.\n")
                .append("What they want: ").append(description.strip()).append('\n')
                .append("Their study topic: ").append(topic);
        if (!page.isTopic()) prompt.append(", the part on ").append(page.getTitle());
        prompt.append('\n');
        if (page.getStudyGoal() != null && !page.getStudyGoal().isBlank()) {
            prompt.append("Their goal: ").append(page.getStudyGoal().strip()).append('\n');
        }
        prompt.append("Prefer reputable pages that are free to read: course notes, references, documentation, ")
                .append("articles and talks with transcripts. For each source write one short line saying ")
                .append("what it covers and why it fits.");
        return prompt.toString();
    }

    private static LinkFetcher.Resolved await(Future<LinkFetcher.Resolved> future, Instant deadline) {
        try {
            long millis = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (Exception e) {
            // Dead, private, too slow, or not something a link source can read: not offered.
            future.cancel(true);
            return null;
        }
    }

    /** The form two links are compared in: no fragment, no trailing slash, host in lower case. */
    static String normalise(String url) {
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return host.replaceFirst("^www\\.", "") + path + query;
        } catch (Exception e) {
            return url.strip();
        }
    }

    private static String domain(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.replaceFirst("^www\\.", "");
    }

    private static String summary(String snippet) {
        if (snippet == null) return "";
        String text = snippet.replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 1).strip() + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return "";
    }

    @PreDestroy
    void shutdown() {
        opening.shutdownNow();
    }
}
