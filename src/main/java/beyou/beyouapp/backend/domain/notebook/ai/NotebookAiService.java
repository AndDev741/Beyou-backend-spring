package beyou.beyouapp.backend.domain.notebook.ai;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.notebook.BlockText;
import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookProgressService;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AiCardsRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AnswerDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CreateFromDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.DraftNodeDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.DraftNodeInputDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.ExplainRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.StudyLevel;
import beyou.beyouapp.backend.domain.notebook.ai.dto.SuggestNodesRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.SuggestedNodeDTO;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNode;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardService;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CreateCardRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * The notebook's AI that is not the study room: drafting a roadmap, suggesting nodes, explaining
 * a block, and drafting flashcards.
 *
 * <p>The model never writes to the database and never picks an id or a coordinate. It proposes
 * text; this class cleans it, and the regular services create what the person accepted. The draft
 * is the onboarding pattern again: stateless, reviewable, nothing stored until "Create".
 */
@Service
@RequiredArgsConstructor
public class NotebookAiService {

    static final int MAX_DRAFT_NODES = 12;
    static final int MAX_SUBTOPICS = 10;
    static final int MAX_SUGGESTIONS = 5;
    static final int DEFAULT_CARDS = 6;

    private final NotebookLlm llm;
    private final StudyContextBuilder contextBuilder;
    private final NotebookOwnership ownership;
    private final NotebookPageService pageService;
    private final NotebookPageRepository pageRepository;
    private final NotebookBoardService boardService;
    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookProgressService progressService;
    private final NotebookCardService cardService;
    private final GoalRepository goalRepository;

    // ------------------------------------------------------------------ draft

    @Transactional(readOnly = true)
    public RoadmapDraftDTO roadmapDraft(User user, RoadmapDraftRequestDTO request) {
        StringBuilder message = new StringBuilder("PLANNING\nPlan a study roadmap.\n")
                .append("Subject: ").append(request.title().strip()).append('\n');
        if (request.why() != null && !request.why().isBlank()) {
            message.append("Why the person is studying it: ").append(request.why().strip()).append('\n');
        }
        message.append("Where they are now: ").append(levelText(request.level())).append('\n');
        if (request.hoursPerWeek() != null) {
            message.append("Time they have: ").append(request.hoursPerWeek()).append(" hours a week\n");
        }
        goalName(user, request.goalId()).ifPresent(goal ->
                message.append("The goal this serves: ").append(goal).append('\n'));
        if (request.references() != null && !request.references().isEmpty()) {
            message.append("They want it based on: ").append(String.join("; ", request.references())).append('\n');
        }
        if (request.previous() != null && !request.previous().isEmpty()) {
            message.append("\nThe current draft, in order:\n");
            for (DraftNodeInputDTO node : request.previous()) {
                message.append("- ").append(node.title());
                if (node.subtopics() != null && !node.subtopics().isEmpty()) {
                    message.append(" (").append(String.join(", ", node.subtopics())).append(')');
                }
                message.append('\n');
            }
            if (request.changeRequest() != null && !request.changeRequest().isBlank()) {
                message.append("The person asked for this change: \"").append(request.changeRequest().strip())
                        .append("\". Apply it and return the WHOLE revised draft.\n");
            }
        }
        message.append("""

                Return 4 to 10 nodes, in the order to study them. Each node: \
                title (max 60 characters), why (one sentence, max 160 characters, saying why this node \
                matters for THEIR aim), subtopics (3 to 8 short titles, max 60 characters each), \
                estimatedHours (integer hours to study it properly at their level), optional (true only \
                when the node could be skipped for the stated aim).
                JSON: {"nodes":[{"title":"","why":"","subtopics":[""],"estimatedHours":10,"optional":false}]}
                """);

        LlmPayloads.RoadmapPayload payload = llm.call(LlmPayloads.RoadmapPayload.class, message.toString(), user);
        List<LlmPayloads.RoadmapNode> raw = payload.nodes() == null ? List.of() : payload.nodes();

        ProgressGraph graph = progressService.graphFor(user.getId());
        Map<String, NotebookPage> existing = existingByTitle(graph);
        List<DraftNodeDTO> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (LlmPayloads.RoadmapNode node : raw) {
            String title = clean(node.title(), 255);
            if (title.isEmpty() || !seen.add(normalise(title))) continue;
            List<String> subtopics = node.subtopics() == null ? List.of() : node.subtopics().stream()
                    .map(s -> clean(s, 255))
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .limit(MAX_SUBTOPICS)
                    .toList();
            int hours = node.estimatedHours() == null ? 8 : Math.max(1, Math.min(200, node.estimatedHours()));
            NotebookPage match = existing.get(normalise(title));
            NotebookPage matchTopic = match == null ? null : match.isTopic() ? match : graph.page(match.getTopicId());
            nodes.add(new DraftNodeDTO(title, clean(node.why(), 500), subtopics, hours,
                    Boolean.TRUE.equals(node.optional()),
                    match == null ? null : match.getId(),
                    matchTopic == null ? null : matchTopic.getTitle(),
                    match == null ? null : graph.progressOf(match.getId())));
            if (nodes.size() >= MAX_DRAFT_NODES) break;
        }
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "The draft came back empty");
        }
        int total = nodes.stream().filter(n -> !n.optional()).mapToInt(DraftNodeDTO::estimatedHours).sum();
        return new RoadmapDraftDTO(nodes, total);
    }

    /**
     * Creates the reviewed draft: the topic, a node per entry on its board, and for every new
     * node a board of its subtopics. Each page opens with the "why" the draft gave it.
     */
    @Transactional
    public PageResponseDTO createFromDraft(User user, CreateFromDraftRequestDTO request) {
        PageResponseDTO created = pageService.createTopic(user, new CreateTopicRequestDTO(
                request.title(), request.description(), request.icon(),
                request.goalId(), request.categoryId(), request.habitId()));
        NotebookPage topic = ownership.topic(user.getId(), created.id());
        topic.setContent(MarkdownBlocks.boardDocument(request.description()));
        topic.setContentText(BlockText.extract(topic.getContent()));

        List<NotebookBoardService.ChainItem> items = new ArrayList<>();
        for (DraftNodeInputDTO node : request.nodes()) {
            if (node.linkPageId() != null) {
                // Ownership is the only check a brand-new topic needs: nothing can reach it yet,
                // so the link cannot close a loop.
                ownership.page(user.getId(), node.linkPageId());
            }
            items.add(new NotebookBoardService.ChainItem(node.title(), node.linkPageId()));
        }
        List<NotebookBoardNode> nodes = boardService.addChain(topic, items);

        // addChain answers one entry per item, so the draft and the nodes pair up by index.
        for (int i = 0; i < nodes.size(); i++) {
            NotebookBoardNode node = nodes.get(i);
            DraftNodeInputDTO input = request.nodes().get(i);
            if (node == null || input.linkPageId() != null) continue;
            NotebookPage page = pageRepository.findById(node.getPageId()).orElseThrow();
            List<String> subtopics = input.subtopics() == null ? List.of() : input.subtopics();
            page.setContent(subtopics.isEmpty()
                    ? MarkdownBlocks.append(null, input.why() == null ? "" : input.why())
                    : MarkdownBlocks.boardDocument(input.why()));
            page.setContentText(BlockText.extract(page.getContent()));
            page.setUpdatedAt(Instant.now());
            if (!subtopics.isEmpty()) {
                boardService.addChain(page, subtopics.stream()
                        .map(s -> new NotebookBoardService.ChainItem(s, null)).toList());
            }
        }
        progressService.boardChanged(user.getId(), topic.getId());
        return pageService.open(user, topic.getId());
    }

    // ---------------------------------------------------------------- suggest

    @Transactional(readOnly = true)
    public List<SuggestedNodeDTO> suggestNodes(User user, UUID pageId, SuggestNodesRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        ProgressGraph graph = progressService.graphFor(user.getId());
        List<String> existing = graph.childrenOf(pageId).stream()
                .map(graph::page).filter(p -> p != null).map(NotebookPage::getTitle).toList();
        NotebookPage topic = page.isTopic() ? page : graph.page(page.getTopicId());

        StringBuilder message = new StringBuilder();
        if (request.fromSources()) {
            StudyContextBuilder.Context context = contextBuilder.forPage(user, page);
            if (context.isEmpty()) {
                throw new BusinessException(ErrorKey.NOTEBOOK_NOTHING_TO_STUDY, "No notes or sources to read");
            }
            message.append("GROUNDED\n").append(context.render());
        } else {
            message.append("PLANNING\n");
        }
        message.append("The roadmap is on the page \"").append(page.getTitle()).append('"');
        if (topic != null && !topic.getId().equals(page.getId())) {
            message.append(", inside the topic \"").append(topic.getTitle()).append('"');
        }
        message.append(".\nNodes already on it: ")
                .append(existing.isEmpty() ? "(none)" : String.join(", ", existing))
                .append(".\nSuggest 3 to 5 NEW nodes worth adding, in the order to study them. Never repeat ")
                .append("an existing node. Each: title (max 60 characters) and why (one sentence, max 160 ")
                .append("characters).\nJSON: {\"suggestions\":[{\"title\":\"\",\"why\":\"\"}]}\n");

        LlmPayloads.SuggestionsPayload payload = llm.call(LlmPayloads.SuggestionsPayload.class, message.toString(), user);
        Set<String> taken = new LinkedHashSet<>();
        existing.forEach(t -> taken.add(normalise(t)));
        List<SuggestedNodeDTO> out = new ArrayList<>();
        for (LlmPayloads.Suggestion s : payload.suggestions() == null ? List.<LlmPayloads.Suggestion>of() : payload.suggestions()) {
            String title = clean(s.title(), 255);
            if (title.isEmpty() || !taken.add(normalise(title))) continue;
            out.add(new SuggestedNodeDTO(title, clean(s.why(), 500)));
            if (out.size() >= MAX_SUGGESTIONS) break;
        }
        return out;
    }

    // ---------------------------------------------------------------- explain

    @Transactional(readOnly = true)
    public AnswerDTO explain(User user, UUID pageId, ExplainRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        StudyContextBuilder.Context context = contextBuilder.forQuestion(user, page, request.text());
        String message = "SUPPORTED\n" + context.render()
                + "Explain this part of the person's notes on \"" + page.getTitle() + "\" so they understand "
                + "it, in at most 200 words:\n\"\"\"\n" + request.text().strip() + "\n\"\"\"\n"
                + "JSON: {\"answer\":\"markdown\",\"citations\":[numbers you used]}\n";
        LlmPayloads.AnswerPayload payload = llm.call(LlmPayloads.AnswerPayload.class, message, user);
        String markdown = contextBuilder.withoutDeadMarkers(context, clean(payload.answer(), 8000));
        return new AnswerDTO(markdown, contextBuilder.citations(context, markdown, payload.citations()));
    }

    // ------------------------------------------------------------------ cards

    @Transactional
    public List<CardDTO> cards(User user, UUID pageId, AiCardsRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        int count = request.count() == null ? DEFAULT_CARDS : request.count();
        boolean selection = request.text() != null && !request.text().isBlank();
        StudyContextBuilder.Context context = selection
                ? contextBuilder.forQuestion(user, page, request.text())
                : contextBuilder.forPage(user, page);
        if (context.isEmpty() && !selection) {
            throw new BusinessException(ErrorKey.NOTEBOOK_NOTHING_TO_STUDY, "No notes or sources to read");
        }
        StringBuilder message = new StringBuilder("GROUNDED\n").append(context.render());
        if (selection) {
            message.append("Focus on this text the person selected:\n\"\"\"\n").append(request.text().strip())
                    .append("\n\"\"\"\n(The selection itself counts as material even where no passage repeats it.)\n");
        }
        message.append("Write ").append(count).append("""
                 flashcards. front: a question that tests understanding rather than the wording \
                (max 200 characters). back: the answer in one to three sentences (max 400 characters). \
                citation: the number of the passage that supports it, or null.
                JSON: {"cards":[{"front":"","back":"","citation":1}]}
                """);
        LlmPayloads.CardsPayload payload = llm.call(LlmPayloads.CardsPayload.class, message.toString(), user);

        Map<Integer, Passage> passages = new HashMap<>();
        context.passages().forEach(p -> passages.put(p.number(), p));
        List<CreateCardRequestDTO> cards = new ArrayList<>();
        for (LlmPayloads.Card card : payload.cards() == null ? List.<LlmPayloads.Card>of() : payload.cards()) {
            String front = clean(card.front(), 2000);
            String back = clean(card.back(), 2000);
            if (front.isEmpty() || back.isEmpty()) continue;
            Passage cited = card.citation() == null ? null : passages.get(card.citation());
            String label = cited == null ? page.getTitle() : cited.label();
            cards.add(new CreateCardRequestDTO(front, back, clean(label, 255)));
            if (cards.size() >= count) break;
        }
        if (cards.isEmpty()) {
            throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "No usable cards came back");
        }
        return cardService.createAll(page, cards, UserDateResolver.today(user));
    }

    // --------------------------------------------------------------- helpers

    /** Every page the user has, by normalised title, the most advanced one winning a tie. */
    private Map<String, NotebookPage> existingByTitle(ProgressGraph graph) {
        Map<String, NotebookPage> byTitle = new HashMap<>();
        for (NotebookPage page : graph.pages()) {
            if (page.isTopic()) continue;
            String key = normalise(page.getTitle());
            NotebookPage current = byTitle.get(key);
            if (current == null || graph.progressOf(page.getId()).done() > graph.progressOf(current.getId()).done()) {
                byTitle.put(key, page);
            }
        }
        return byTitle;
    }

    /**
     * A title reduced to what makes two titles "the same subject": no case, no accents, no
     * punctuation, no plural s. "Operating Systems" and "operating system" match; "Sistemas
     * Operacionais" does not, and that is fine: a missed link is a duplicate the person can see.
     */
    static String normalise(String title) {
        if (title == null) return "";
        String plain = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder out = new StringBuilder();
        for (String word : plain.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            if (word.length() > 3 && word.endsWith("s")) word = word.substring(0, word.length() - 1);
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.toString();
    }

    private Optional<String> goalName(User user, UUID goalId) {
        if (goalId == null) return Optional.empty();
        return goalRepository.findById(goalId)
                .filter(g -> g.getUser().getId().equals(user.getId()))
                .map(g -> g.getName());
    }

    private static String levelText(StudyLevel level) {
        if (level == null) return "not stated";
        return switch (level) {
            case NEW -> "new to the subject";
            case SOME -> "knows some of it already";
            case SOLID -> "solid foundations, wants depth";
        };
    }

    static String clean(String text, int max) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max).strip();
    }
}
