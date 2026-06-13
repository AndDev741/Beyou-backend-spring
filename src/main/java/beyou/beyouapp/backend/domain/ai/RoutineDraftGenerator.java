package beyou.beyouapp.backend.domain.ai;

import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;

/**
 * Abstraction over the AI provider. Production uses SpringAiRoutineDraftGenerator
 * (ChatClient); the e2e profile swaps in CannedRoutineDraftGenerator so Playwright
 * runs are deterministic and free.
 */
public interface RoutineDraftGenerator {

    RoutineDraftDTO generate(GenerationContext context);

    record GenerationContext(
            String description,
            RoutineDraftDTO previousDraft,
            String feedback,
            String language,
            String userContextJson) {
    }
}
