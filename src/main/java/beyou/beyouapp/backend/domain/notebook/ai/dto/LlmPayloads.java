package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;

/**
 * The shapes the model is asked to answer in. Never sent to a client as they are: every one is
 * cleaned and capped by {@code NotebookAiService} first, because free-tier output is not trusted.
 */
public final class LlmPayloads {

    private LlmPayloads() {}

    public record RoadmapPayload(List<RoadmapNode> nodes) {
    }

    public record RoadmapNode(String title, String why, List<String> subtopics, Integer estimatedHours,
            Boolean optional) {
    }

    public record SuggestionsPayload(List<Suggestion> suggestions) {
    }

    public record Suggestion(String title, String why) {
    }

    /** Explain, summary and study guide. The studio's outputs do not draw, so no diagram here. */
    public record AnswerPayload(String answer, List<Integer> citations) {
    }

    /**
     * The study room's chat: an answer, and a diagram when one helps. Its own record so the
     * studio's outputs, which share {@link AnswerPayload}, are never asked for a diagram.
     */
    public record ChatPayload(String answer, List<Integer> citations, DiagramSpec diagram) {
    }

    public record CardsPayload(List<Card> cards) {
    }

    public record Card(String front, String back, Integer citation) {
    }

    public record QuizPayload(List<QuizQuestion> questions) {
    }

    public record QuizQuestion(String question, List<String> options, Integer answerIndex, String explanation,
            Integer citation) {
    }

    public record OverviewPayload(String summary, List<String> questions) {
    }
}
