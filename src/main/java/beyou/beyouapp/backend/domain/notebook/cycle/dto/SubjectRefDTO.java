package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

/**
 * A cycle's subject, named: any notebook page, a topic or one inside it. Sent wherever a cycle
 * payload says {@code topic}.
 *
 * @param rootTitle the title of the topic the subject lives in, or null when the subject IS a
 *                  topic. A subject is usually an area of a bigger topic ("Implementação 2.0"
 *                  inside "Software Engineer"), and the title alone can repeat across topics, so
 *                  the clients print the root as a prefix
 */
public record SubjectRefDTO(UUID id, String title, String icon, String rootTitle) {
}
