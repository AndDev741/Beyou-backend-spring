package beyou.beyouapp.backend.domain.notebook.study;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyScopeOptionDTO;
import lombok.RequiredArgsConstructor;

/**
 * Which pages' notes the study AI reads, for each {@link StudyScope}.
 *
 * <p>Every scope starts from the page and the pages above it, which is what the room always read:
 * a topic's description and a parent's notes are context for the page below. SUBTREE adds every
 * page under the page and TOPIC every page of the topic. The page itself is always first.
 */
@Component
@RequiredArgsConstructor
public class StudyScopes {

    private final NotebookSourceService sourceService;
    private final NotebookPageRepository pageRepository;

    public List<NotebookPage> pagesFor(NotebookPage page, StudyScope scope) {
        Map<UUID, NotebookPage> pages = new LinkedHashMap<>();
        pages.put(page.getId(), page);
        sourceService.scope(page).values().forEach(p -> pages.putIfAbsent(p.getId(), p));
        if (scope == null || scope == StudyScope.PAGE) return new ArrayList<>(pages.values());

        UUID topicId = page.isTopic() ? page.getId() : page.getTopicId();
        List<NotebookPage> topicPages = pageRepository.findByTopicIdOrderByPositionAscCreatedAtAsc(topicId);
        if (scope == StudyScope.TOPIC) {
            topicPages.forEach(p -> pages.putIfAbsent(p.getId(), p));
            return new ArrayList<>(pages.values());
        }
        Map<UUID, List<NotebookPage>> children = new HashMap<>();
        for (NotebookPage p : topicPages) {
            if (p.getParentId() != null) children.computeIfAbsent(p.getParentId(), k -> new ArrayList<>()).add(p);
        }
        List<NotebookPage> stack = new ArrayList<>(children.getOrDefault(page.getId(), List.of()));
        while (!stack.isEmpty()) {
            NotebookPage next = stack.remove(stack.size() - 1);
            if (pages.putIfAbsent(next.getId(), next) == null) {
                stack.addAll(children.getOrDefault(next.getId(), List.of()));
            }
        }
        return new ArrayList<>(pages.values());
    }

    /** What each scope would read, for the setup screen. */
    public List<StudyScopeOptionDTO> options(NotebookPage page) {
        List<StudyScopeOptionDTO> options = new ArrayList<>();
        for (StudyScope scope : StudyScope.values()) {
            int pagesWithNotes = 0;
            int words = 0;
            for (NotebookPage p : pagesFor(page, scope)) {
                String text = p.getContentText();
                if (text == null || text.isBlank()) continue;
                pagesWithNotes++;
                words += text.strip().split("\\s+").length;
            }
            options.add(new StudyScopeOptionDTO(scope, pagesWithNotes, words));
        }
        return options;
    }
}
