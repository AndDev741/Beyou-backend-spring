package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;

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

    public record AnswerPayload(String answer, List<Integer> citations) {
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
