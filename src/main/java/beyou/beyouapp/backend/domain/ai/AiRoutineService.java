package beyou.beyouapp.backend.domain.ai;

import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.ai.RoutineDraftGenerator.GenerationContext;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineRequestDTO;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.ai.AiGenerationException;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stateless generate pipeline: user context → generator (1 retry) → validator.
 * Persists NOTHING — the draft only becomes real through AiRoutineConfirmService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiRoutineService {

    private final RoutineDraftGenerator generator;
    private final AiDraftValidator validator;
    private final AiUserContextBuilder contextBuilder;

    public GenerateRoutineResponseDTO generate(GenerateRoutineRequestDTO request, User user) {
        String contextJson = contextBuilder.build(user.getId());
        String language = request.language() != null ? request.language() : "en";
        GenerationContext ctx = new GenerationContext(
                request.description(), request.previousDraft(), request.feedback(), language, contextJson);

        RoutineDraftDTO raw = generateWithRetry(ctx);
        RoutineDraftDTO sanitized;
        try {
            sanitized = validator.validateAndSanitize(raw, user.getId(), ErrorKey.AI_RESPONSE_INVALID);
        } catch (BusinessException validationFailure) {
            if (validationFailure.getErrorKey() != ErrorKey.AI_RESPONSE_INVALID) {
                throw validationFailure;
            }
            // The model produced a structurally broken draft (it happens) —
            // one fresh attempt usually fixes it; if not, the error propagates.
            log.warn("AI draft failed validation, regenerating once: {}", validationFailure.getMessage());
            raw = generator.generate(ctx);
            sanitized = validator.validateAndSanitize(raw, user.getId(), ErrorKey.AI_RESPONSE_INVALID);
        }
        return new GenerateRoutineResponseDTO(sanitized);
    }

    private RoutineDraftDTO generateWithRetry(GenerationContext ctx) {
        try {
            return generator.generate(ctx);
        } catch (Exception first) {
            log.warn("AI generation failed, retrying once: {}", first.getMessage());
            try {
                return generator.generate(ctx);
            } catch (Exception second) {
                throw new AiGenerationException("AI generation failed after retry", second);
            }
        }
    }
}
