package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.time.Instant;

import beyou.beyouapp.backend.domain.notebook.study.StudyScope;

/**
 * The study room's setup on a page.
 *
 * @param goal         what the person wants out of studying it, or null
 * @param configuredAt when it was last saved; null until the person sets the room up, and the
 *                     room opens on its setup screen while it is
 */
public record StudySetupDTO(String goal, StudyScope scope, Instant configuredAt) {
}
