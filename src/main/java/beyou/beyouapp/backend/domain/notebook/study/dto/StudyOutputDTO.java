package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;
import beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind;

/**
 * Something the studio made. A SUMMARY or STUDY_GUIDE carries {@code markdown}; a QUIZ carries
 * {@code questions} (answers stay on the server until {@code quiz-result}) and, once taken, its
 * last {@code score} out of {@code total}.
 *
 * @param title the page's title; the client prefixes it with the kind in the reader's language
 */
public record StudyOutputDTO(
        UUID id,
        UUID pageId,
        StudyOutputKind kind,
        String title,
        String markdown,
        List<CitationDTO> citations,
        List<QuizQuestionDTO> questions,
        Integer score,
        Integer total,
        Instant passedAt,
        Instant createdAt) {
}
