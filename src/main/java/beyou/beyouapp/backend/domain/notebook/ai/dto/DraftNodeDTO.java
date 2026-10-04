package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.dto.ProgressDTO;

/**
 * One node of a drafted roadmap.
 *
 * @param optional        the model marked it skippable for the stated aim; the dialog unticks it
 * @param existingPageId  a page the person already has with the same subject, offered as a link
 * @param existingTopicTitle that page's topic
 * @param existingProgress that page's progress, for "1 of 6 done"
 */
public record DraftNodeDTO(
        String title,
        String why,
        List<String> subtopics,
        int estimatedHours,
        boolean optional,
        UUID existingPageId,
        String existingTopicTitle,
        ProgressDTO existingProgress) {
}
