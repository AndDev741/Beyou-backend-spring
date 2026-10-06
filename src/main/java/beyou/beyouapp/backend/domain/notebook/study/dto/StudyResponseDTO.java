package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.SourceDTO;

/**
 * The study room in one call: the page, its sources, the chat so far, and what the studio made.
 *
 * @param setup     the room's goal and notes scope; {@code configuredAt} null opens the setup screen
 * @param scopes    what each notes scope would read, for the setup screen
 * @param discovery whether "find sources for me" has a search configured
 */
public record StudyResponseDTO(
        PageRefDTO page,
        List<PageRefDTO> breadcrumb,
        OverviewDTO overview,
        List<ChatMessageDTO> messages,
        List<StudyOutputDTO> outputs,
        List<SourceDTO> sources,
        int cardsTotal,
        int cardsDue,
        StudySetupDTO setup,
        List<StudyScopeOptionDTO> scopes,
        boolean discovery) {
}
