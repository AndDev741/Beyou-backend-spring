package beyou.beyouapp.backend.docs.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.api.ApiControllerTopicRepository;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerStatus;
import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicRepository;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.docs.design.DesignTopicRepository;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicStatus;
import beyou.beyouapp.backend.docs.project.ProjectTopicRepository;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicStatus;
import beyou.beyouapp.backend.docs.search.dto.SearchHighlightDTO;
import beyou.beyouapp.backend.docs.search.dto.SearchRequestDTO;
import beyou.beyouapp.backend.docs.search.dto.SearchResultDTO;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final ArchitectureTopicRepository architectureRepository;
    private final DesignTopicRepository designRepository;
    private final ApiControllerTopicRepository apiRepository;
    private final ProjectTopicRepository projectRepository;

    @Transactional(readOnly = true)
    public List<SearchResultDTO> search(SearchRequestDTO request) {
        String query = request.q().trim();
        if (query.length() < 2) {
            throw new IllegalArgumentException("Search query must be at least 2 characters");
        }

        String locale = request.locale() != null ? request.locale() : "en";
        String category = request.category() != null ? request.category() : "all";
        // Normalize plural category names to singular
        if ("designs".equals(category)) {
            category = "design";
        } else if ("apis".equals(category)) {
            category = "api";
        } else if ("projects".equals(category)) {
            category = "project";
        }
        int limit = request.limit() != null ? request.limit() : 10;
        int offset = request.offset() != null ? request.offset() : 0;

        List<SearchResultDTO> allResults = new ArrayList<>();

        if (category.equals("all") || category.equals("architecture")) {
            allResults.addAll(searchArchitecture(query, locale));
        }
        if (category.equals("all") || category.equals("design")) {
            allResults.addAll(searchDesign(query, locale));
        }
        if (category.equals("all") || category.equals("api")) {
            allResults.addAll(searchApi(query, locale));
        }
        if (category.equals("all") || category.equals("project")) {
            allResults.addAll(searchProject(query, locale));
        }

        // Sort by score descending
        allResults.sort(Comparator.comparingDouble(SearchResultDTO::score).reversed());

        // Apply pagination
        int fromIndex = Math.min(offset, allResults.size());
        int toIndex = Math.min(offset + limit, allResults.size());
        return allResults.subList(fromIndex, toIndex);
    }

    private List<SearchResultDTO> searchArchitecture(String query, String locale) {
        return architectureRepository
            .searchByLocaleAndQuery(ArchitectureTopicStatus.ACTIVE, locale, "%" + query + "%")
            .stream()
            .map(topic -> {
                var content = topic.findContentByLocale(locale)
                    .or(() -> topic.findContentByLocale("en"))
                    .orElseThrow(() -> new IllegalStateException("No content found"));
                double score = computeScore(content.getTitle(), content.getSummary(), query);
                var highlight = computeHighlight(content.getTitle(), content.getSummary(), query);
                return new SearchResultDTO(
                    "architecture",
                    topic.getKey(),
                    content.getTitle(),
                    content.getSummary(),
                    content.getUpdatedAt(),
                    score,
                    highlight
                );
            })
            .collect(Collectors.toList());
    }

    private List<SearchResultDTO> searchDesign(String query, String locale) {
        return designRepository
            .searchByLocaleAndQuery(DesignTopicStatus.ACTIVE, locale, "%" + query + "%")
            .stream()
            .map(topic -> {
                var content = topic.findContentByLocale(locale)
                    .or(() -> topic.findContentByLocale("en"))
                    .orElseThrow(() -> new IllegalStateException("No content found"));
                double score = computeScore(content.getTitle(), content.getSummary(), query);
                var highlight = computeHighlight(content.getTitle(), content.getSummary(), query);
                return new SearchResultDTO(
                    "design",
                    topic.getKey(),
                    content.getTitle(),
                    content.getSummary(),
                    content.getUpdatedAt(),
                    score,
                    highlight
                );
            })
            .collect(Collectors.toList());
    }

    private List<SearchResultDTO> searchApi(String query, String locale) {
        return apiRepository
            .searchByLocaleAndQuery(ApiControllerStatus.ACTIVE, locale, "%" + query + "%")
            .stream()
            .map(topic -> {
                var content = topic.findContentByLocale(locale)
                    .or(() -> topic.findContentByLocale("en"))
                    .orElseThrow(() -> new IllegalStateException("No content found"));
                double score = computeScore(content.getTitle(), content.getSummary(), query);
                var highlight = computeHighlight(content.getTitle(), content.getSummary(), query);
                return new SearchResultDTO(
                    "api",
                    topic.getKey(),
                    content.getTitle(),
                    content.getSummary(),
                    content.getUpdatedAt(),
                    score,
                    highlight
                );
            })
            .collect(Collectors.toList());
    }

    private List<SearchResultDTO> searchProject(String query, String locale) {
        return projectRepository
            .searchByLocaleAndQuery(ProjectTopicStatus.ACTIVE, locale, "%" + query + "%")
            .stream()
            .map(topic -> {
                var content = topic.findContentByLocale(locale)
                    .or(() -> topic.findContentByLocale("en"))
                    .orElseThrow(() -> new IllegalStateException("No content found"));
                double score = computeScore(content.getTitle(), content.getSummary(), query);
                var highlight = computeHighlight(content.getTitle(), content.getSummary(), query);
                return new SearchResultDTO(
                    "project",
                    topic.getKey(),
                    content.getTitle(),
                    content.getSummary(),
                    content.getUpdatedAt(),
                    score,
                    highlight
                );
            })
            .collect(Collectors.toList());
    }

    private double computeScore(String title, String summary, String query) {
        String lowerTitle = title.toLowerCase();
        String lowerSummary = summary != null ? summary.toLowerCase() : "";
        String lowerQuery = query.toLowerCase();

        if (lowerTitle.contains(lowerQuery)) {
            return 1.0;
        } else if (lowerSummary.contains(lowerQuery)) {
            return 0.5;
        } else {
            return 0.0;
        }
    }

    private SearchHighlightDTO computeHighlight(String title, String summary, String query) {
        return new SearchHighlightDTO(
            highlightText(title, query),
            highlightText(summary != null ? summary : "", query)
        );
    }

    private List<String> highlightText(String text, String query) {
        if (text.isEmpty() || query.isEmpty()) {
            return List.of(text);
        }
        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        List<String> fragments = new ArrayList<>();
        int lastIndex = 0;
        int idx = lowerText.indexOf(lowerQuery);
        while (idx >= 0) {
            // Add preceding non-matching part
            if (idx > lastIndex) {
                fragments.add(text.substring(lastIndex, idx));
            }
            // Add matching part wrapped with <mark>
            fragments.add("<mark>" + text.substring(idx, idx + query.length()) + "</mark>");
            lastIndex = idx + query.length();
            idx = lowerText.indexOf(lowerQuery, lastIndex);
        }
        // Add remaining part
        if (lastIndex < text.length()) {
            fragments.add(text.substring(lastIndex));
        }
        if (fragments.isEmpty()) {
            fragments.add(text);
        }
        return fragments;
    }
}