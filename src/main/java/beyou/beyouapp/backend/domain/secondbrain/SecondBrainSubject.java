package beyou.beyouapp.backend.domain.secondbrain;

import java.util.UUID;

/**
 * A habit, goal, routine or category that exists, as a note or a prompt names it.
 *
 * @param name the thing's current name
 */
public record SecondBrainSubject(SecondBrainSubjectType type, UUID id, String name) {}
