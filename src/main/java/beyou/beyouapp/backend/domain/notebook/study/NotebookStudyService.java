package beyou.beyouapp.backend.domain.notebook.study;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookProgressService;
import beyou.beyouapp.backend.domain.notebook.NotebookRewards;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.StudyContextBuilder;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardRepository;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.study.dto.ChatMessageDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.ChatTurnDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.OverviewDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.QuizAnswerDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.QuizQuestionDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.QuizResultDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyOutputDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The study room: grounded chat on a page, and the studio's outputs.
 *
 * <p>Grounded means the model is given numbered passages (the page's notes and the sources it may
 * read) and told to answer only from them, citing [n]. {@link StudyContextBuilder} owns the
 * numbering and drops any citation the model invents.
 *
 * <p>Outputs are stored as JSON (see the Stored records below). A quiz keeps its answers on the
 * server: the client gets the questions, sends its picks to {@link #grade}, and only then sees
 * what was right. That is also where the 20 XP is paid, once per quiz.
 */
@Service
@RequiredArgsConstructor
public class NotebookStudyService {

    /** Messages a study-room read returns. */
    static final int HISTORY_LIMIT = 40;
    /** Earlier turns sent to the model with a new question, for "and what about...?". */
    static final int CONTEXT_TURNS = 6;
    static final int QUIZ_QUESTIONS = 8;
    /** Seventy per cent, as integer arithmetic: score * 10 >= total * 7. */
    static final int PASS_NUMERATOR = 7;

    private final NotebookOwnership ownership;
    private final NotebookPageService pageService;
    private final NotebookProgressService progressService;
    private final NotebookSourceService sourceService;
    private final NotebookCardRepository cardRepository;
    private final NotebookStudyOutputRepository outputRepository;
    private final NotebookChatMessageRepository messageRepository;
    private final StudyContextBuilder contextBuilder;
    private final NotebookLlm llm;
    private final NotebookRewards rewards;
    private final ObjectMapper objectMapper;

    record StoredAnswer(String markdown, List<CitationDTO> citations) {
    }

    record StoredQuestion(String question, List<String> options, int answerIndex, String explanation,
            CitationDTO citation) {
    }

    record StoredQuiz(List<StoredQuestion> questions) {
    }

    record StoredOverview(String summary, List<String> questions, List<CitationDTO> citations) {
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public StudyResponseDTO study(User user, UUID pageId) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        ProgressGraph graph = progressService.graphFor(user.getId());
        OverviewDTO overview = outputRepository
                .findFirstByPageIdAndKindOrderByCreatedAtDesc(pageId, StudyOutputKind.OVERVIEW)
                .map(this::toOverview)
                .orElse(null);
        List<ChatMessageDTO> messages = new ArrayList<>(messageRepository
                .findByPageIdOrderByCreatedAtDesc(pageId, PageRequest.of(0, HISTORY_LIMIT)).stream()
                .map(this::toMessage)
                .toList());
        Collections.reverse(messages);
        List<StudyOutputDTO> outputs = outputRepository
                .findByPageIdAndKindNotOrderByCreatedAtDesc(pageId, StudyOutputKind.OVERVIEW).stream()
                .map(this::toOutput)
                .toList();
        return new StudyResponseDTO(
                NotebookPageService.ref(page),
                pageService.breadcrumb(graph, page),
                overview,
                messages,
                outputs,
                sourceService.list(user, pageId),
                (int) cardRepository.countByPageId(pageId),
                (int) cardRepository.countByPageIdAndDueOnLessThanEqual(pageId, UserDateResolver.today(user)));
    }

    @Transactional(readOnly = true)
    public StudyOutputDTO output(User user, UUID outputId) {
        return toOutput(ownedOutput(user, outputId));
    }

    // ------------------------------------------------------------------ chat

    @Transactional
    public ChatTurnDTO chat(User user, UUID pageId, String question) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        StudyContextBuilder.Context context = contextBuilder.forQuestion(user, page, question);
        if (context.isEmpty()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_NOTHING_TO_STUDY, "No notes or sources to answer from");
        }
        List<NotebookChatMessage> earlier = new ArrayList<>(messageRepository
                .findByPageIdOrderByCreatedAtDesc(pageId, PageRequest.of(0, CONTEXT_TURNS)));
        Collections.reverse(earlier);

        StringBuilder message = new StringBuilder("GROUNDED\n").append(context.render());
        if (!earlier.isEmpty()) {
            message.append("The conversation so far:\n");
            for (NotebookChatMessage m : earlier) {
                message.append(NotebookChatMessage.USER.equals(m.getRole()) ? "Person: " : "Tutor: ")
                        .append(m.getContent()).append('\n');
            }
        }
        message.append("\nThe person asks: ").append(question.strip()).append('\n')
                .append("Answer in at most 250 words.\n")
                .append("JSON: {\"answer\":\"markdown\",\"citations\":[numbers you used]}\n");
        LlmPayloads.AnswerPayload payload = llm.call(LlmPayloads.AnswerPayload.class, message.toString(), user);
        String markdown = contextBuilder.withoutDeadMarkers(context, clip(payload.answer(), 8000));
        if (markdown.isBlank()) {
            throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "The answer came back empty");
        }
        List<CitationDTO> citations = contextBuilder.citations(context, markdown, payload.citations());

        NotebookChatMessage asked = save(page, NotebookChatMessage.USER, question.strip(), null);
        NotebookChatMessage answered = save(page, NotebookChatMessage.ASSISTANT, markdown,
                objectMapper.writeValueAsString(citations));
        return new ChatTurnDTO(toMessage(asked), toMessage(answered));
    }

    @Transactional
    public void clearChat(User user, UUID pageId) {
        ownership.page(user.getId(), pageId);
        messageRepository.deleteByPageId(pageId);
    }

    private NotebookChatMessage save(NotebookPage page, String role, String content, String citations) {
        NotebookChatMessage m = new NotebookChatMessage();
        m.setUser(page.getUser());
        m.setPageId(page.getId());
        m.setRole(role);
        m.setContent(content);
        m.setCitations(citations);
        // Distinct instants, so the question always sorts before its answer.
        m.setCreatedAt(Instant.now().plusNanos(NotebookChatMessage.USER.equals(role) ? 0 : 1000));
        return messageRepository.save(m);
    }

    // --------------------------------------------------------------- studio

    @Transactional
    public StudyOutputDTO generate(User user, UUID pageId, StudyOutputKind kind) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        StudyContextBuilder.Context context = contextBuilder.forPage(user, page);
        if (context.isEmpty()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_NOTHING_TO_STUDY, "No notes or sources to read");
        }
        String content = switch (kind) {
            case OVERVIEW -> overview(user, context);
            case SUMMARY -> answer(user, context, page, """
                    Summarise the passages for someone studying "%s": the main ideas in a few short \
                    paragraphs or bullets, at most 300 words.""");
            case STUDY_GUIDE -> answer(user, context, page, """
                    Write a study guide for "%s" from the passages: ## Key ideas (bullets), \
                    ## Terms to know (bullets, term in bold then a short definition), and \
                    ## Questions to check yourself (4 to 6). At most 500 words.""");
            case QUIZ -> quiz(user, context);
        };
        if (kind == StudyOutputKind.OVERVIEW) {
            // One overview per page: a new one replaces the last instead of piling up.
            outputRepository.deleteByPageIdAndKind(pageId, StudyOutputKind.OVERVIEW);
        }
        NotebookStudyOutput output = new NotebookStudyOutput();
        output.setUser(page.getUser());
        output.setPageId(pageId);
        output.setKind(kind);
        output.setTitle(page.getTitle());
        output.setContent(content);
        output.setCreatedAt(Instant.now());
        return toOutput(outputRepository.save(output));
    }

    private String overview(User user, StudyContextBuilder.Context context) {
        String message = "GROUNDED\n" + context.render() + """
                Say in two or three sentences what these passages cover, for someone deciding what to \
                ask about. Then write 2 or 3 short questions worth asking about them (max 80 characters each).
                JSON: {"summary":"","questions":[""]}
                """;
        LlmPayloads.OverviewPayload payload = llm.call(LlmPayloads.OverviewPayload.class, message, user);
        String summary = contextBuilder.withoutDeadMarkers(context, clip(payload.summary(), 1200));
        List<String> questions = payload.questions() == null ? List.of() : payload.questions().stream()
                .map(q -> clip(q, 200)).filter(q -> !q.isEmpty()).limit(3).toList();
        return objectMapper.writeValueAsString(new StoredOverview(summary, questions,
                contextBuilder.citations(context, summary, null)));
    }

    private String answer(User user, StudyContextBuilder.Context context, NotebookPage page, String task) {
        String message = "GROUNDED\n" + context.render() + task.formatted(page.getTitle())
                + "\nJSON: {\"answer\":\"markdown\",\"citations\":[numbers you used]}\n";
        LlmPayloads.AnswerPayload payload = llm.call(LlmPayloads.AnswerPayload.class, message, user);
        String markdown = contextBuilder.withoutDeadMarkers(context, clip(payload.answer(), 12000));
        if (markdown.isBlank()) {
            throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "The output came back empty");
        }
        return objectMapper.writeValueAsString(new StoredAnswer(markdown,
                contextBuilder.citations(context, markdown, payload.citations())));
    }

    private String quiz(User user, StudyContextBuilder.Context context) {
        String message = "GROUNDED\n" + context.render() + """
                Write %d multiple-choice questions that test understanding of the passages, not their \
                wording. Each: question (max 240 characters), options (exactly 4, max 120 characters \
                each, one clearly right), answerIndex (0 to 3), explanation (one or two sentences, max \
                300 characters), citation (the supporting passage number).
                JSON: {"questions":[{"question":"","options":["","","",""],"answerIndex":0,"explanation":"","citation":1}]}
                """.formatted(QUIZ_QUESTIONS);
        LlmPayloads.QuizPayload payload = llm.call(LlmPayloads.QuizPayload.class, message, user);
        List<StoredQuestion> questions = new ArrayList<>();
        for (LlmPayloads.QuizQuestion q : payload.questions() == null ? List.<LlmPayloads.QuizQuestion>of() : payload.questions()) {
            String text = clip(q.question(), 400);
            List<String> options = q.options() == null ? List.of()
                    : q.options().stream().map(o -> clip(o, 200)).filter(o -> !o.isEmpty()).distinct().toList();
            // A question without four distinct options, or whose answer points outside them, cannot
            // be graded honestly. Dropped rather than repaired.
            if (text.isEmpty() || options.size() != 4 || q.answerIndex() == null
                    || q.answerIndex() < 0 || q.answerIndex() > 3) {
                continue;
            }
            CitationDTO citation = q.citation() == null ? null
                    : contextBuilder.citations(context, "", List.of(q.citation())).stream().findFirst().orElse(null);
            questions.add(new StoredQuestion(text, options, q.answerIndex(), clip(q.explanation(), 600), citation));
            if (questions.size() >= QUIZ_QUESTIONS) break;
        }
        if (questions.size() < 3) {
            throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "Not enough usable questions came back");
        }
        return objectMapper.writeValueAsString(new StoredQuiz(questions));
    }

    /** Grades a quiz. The first pass pays {@link NotebookRewards#QUIZ_PASSED_XP}; retakes keep the score. */
    @Transactional
    public QuizResultDTO grade(User user, UUID outputId, List<Integer> answers) {
        NotebookStudyOutput output = ownedOutput(user, outputId);
        if (output.getKind() != StudyOutputKind.QUIZ) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Only a quiz can be graded");
        }
        StoredQuiz quiz = objectMapper.readValue(output.getContent(), StoredQuiz.class);
        List<QuizAnswerDTO> graded = new ArrayList<>();
        int score = 0;
        for (int i = 0; i < quiz.questions().size(); i++) {
            StoredQuestion q = quiz.questions().get(i);
            int chosen = i < answers.size() && answers.get(i) != null ? answers.get(i) : -1;
            boolean right = chosen == q.answerIndex();
            if (right) score++;
            graded.add(new QuizAnswerDTO(i, chosen, q.answerIndex(), right, q.explanation(), q.citation()));
        }
        int total = quiz.questions().size();
        boolean passed = score * 10 >= total * PASS_NUMERATOR;
        output.setScore(score);
        output.setTotal(total);

        double xp = 0;
        RefreshUiDTO refresh = null;
        if (passed && output.getPassedAt() == null) {
            output.setPassedAt(Instant.now());
            NotebookPage page = ownership.page(user.getId(), output.getPageId());
            NotebookPage topic = page.isTopic() ? page : ownership.page(user.getId(), page.getTopicId());
            refresh = rewards.pay(page.getUser(), topic.getCategory(), NotebookRewards.QUIZ_PASSED_XP);
            xp = NotebookRewards.QUIZ_PASSED_XP;
        }
        return new QuizResultDTO(score, total, passed, graded, xp, refresh);
    }

    @Transactional
    public void deleteOutput(User user, UUID outputId) {
        outputRepository.delete(ownedOutput(user, outputId));
    }

    // --------------------------------------------------------------- helpers

    private NotebookStudyOutput ownedOutput(User user, UUID outputId) {
        NotebookStudyOutput output = outputRepository.findById(outputId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_OUTPUT_NOT_FOUND, "Output not found"));
        ownership.page(user.getId(), output.getPageId());
        return output;
    }

    private ChatMessageDTO toMessage(NotebookChatMessage m) {
        List<CitationDTO> citations = m.getCitations() == null ? List.of()
                : objectMapper.readValue(m.getCitations(), new TypeReference<List<CitationDTO>>() { });
        return new ChatMessageDTO(m.getId(), m.getRole(), m.getContent(), citations, m.getCreatedAt());
    }

    private OverviewDTO toOverview(NotebookStudyOutput o) {
        StoredOverview stored = objectMapper.readValue(o.getContent(), StoredOverview.class);
        return new OverviewDTO(o.getId(), stored.summary(), stored.questions(), stored.citations(), o.getCreatedAt());
    }

    private StudyOutputDTO toOutput(NotebookStudyOutput o) {
        if (o.getKind() == StudyOutputKind.QUIZ) {
            StoredQuiz quiz = objectMapper.readValue(o.getContent(), StoredQuiz.class);
            List<QuizQuestionDTO> questions = new ArrayList<>();
            for (int i = 0; i < quiz.questions().size(); i++) {
                StoredQuestion q = quiz.questions().get(i);
                questions.add(new QuizQuestionDTO(i, q.question(), q.options()));
            }
            return new StudyOutputDTO(o.getId(), o.getPageId(), o.getKind(), o.getTitle(), null, List.of(),
                    questions, o.getScore(), o.getTotal(), o.getPassedAt(), o.getCreatedAt());
        }
        if (o.getKind() == StudyOutputKind.OVERVIEW) {
            StoredOverview overview = objectMapper.readValue(o.getContent(), StoredOverview.class);
            return new StudyOutputDTO(o.getId(), o.getPageId(), o.getKind(), o.getTitle(), overview.summary(),
                    overview.citations(), null, null, null, null, o.getCreatedAt());
        }
        StoredAnswer answer = objectMapper.readValue(o.getContent(), StoredAnswer.class);
        return new StudyOutputDTO(o.getId(), o.getPageId(), o.getKind(), o.getTitle(), answer.markdown(),
                answer.citations(), null, null, null, null, o.getCreatedAt());
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max).strip();
    }
}
