package beyou.beyouapp.backend.unit.ai;

import beyou.beyouapp.backend.domain.ai.AiDraftValidator;
import beyou.beyouapp.backend.domain.ai.AiRoutineService;
import beyou.beyouapp.backend.domain.ai.AiUserContextBuilder;
import beyou.beyouapp.backend.domain.ai.RoutineDraftGenerator;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineRequestDTO;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.ai.AiGenerationException;
import beyou.beyouapp.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRoutineServiceTest {

    @Mock private RoutineDraftGenerator generator;
    @Mock private AiDraftValidator validator;
    @Mock private AiUserContextBuilder contextBuilder;

    private AiRoutineService service;
    private User user;
    private RoutineDraftDTO rawDraft;
    private RoutineDraftDTO sanitizedDraft;
    private GenerateRoutineRequestDTO request;

    @BeforeEach
    void setUp() {
        service = new AiRoutineService(generator, validator, contextBuilder);
        user = new User();
        user.setId(UUID.randomUUID());
        rawDraft = new RoutineDraftDTO("Raw", null, List.of(), List.of(), null);
        sanitizedDraft = new RoutineDraftDTO("Sanitized", null, List.of(), List.of(), null);
        request = new GenerateRoutineRequestDTO("I wake up at 6am and want a productive morning",
                null, null, "en");
        when(contextBuilder.build(user.getId())).thenReturn("{}");
    }

    @Test
    void generatesValidatesAndReturnsSanitizedDraft() {
        when(generator.generate(any())).thenReturn(rawDraft);
        when(validator.validateAndSanitize(eq(rawDraft), eq(user.getId()), eq(ErrorKey.AI_RESPONSE_INVALID)))
                .thenReturn(sanitizedDraft);

        GenerateRoutineResponseDTO response = service.generate(request, user);

        assertEquals(sanitizedDraft, response.draft());
        verify(generator, times(1)).generate(any());
    }

    @Test
    void retriesOnceThenSucceeds() {
        when(generator.generate(any()))
                .thenThrow(new RuntimeException("provider hiccup"))
                .thenReturn(rawDraft);
        when(validator.validateAndSanitize(any(), any(), any())).thenReturn(sanitizedDraft);

        GenerateRoutineResponseDTO response = service.generate(request, user);

        assertEquals(sanitizedDraft, response.draft());
        verify(generator, times(2)).generate(any());
    }

    @Test
    void throwsAiGenerationExceptionAfterSecondFailure() {
        when(generator.generate(any())).thenThrow(new RuntimeException("down"));

        assertThrows(AiGenerationException.class, () -> service.generate(request, user));
        verify(generator, times(2)).generate(any());
    }

    @Test
    void regeneratesOnceWhenDraftFailsValidation() {
        when(generator.generate(any())).thenReturn(rawDraft);
        when(validator.validateAndSanitize(any(), any(), any()))
                .thenThrow(new beyou.beyouapp.backend.exceptions.BusinessException(
                        ErrorKey.AI_RESPONSE_INVALID, "broken draft"))
                .thenReturn(sanitizedDraft);

        GenerateRoutineResponseDTO response = service.generate(request, user);

        assertEquals(sanitizedDraft, response.draft());
        verify(generator, times(2)).generate(any());
    }

    @Test
    void propagatesValidationFailureWhenRegenerationAlsoFails() {
        when(generator.generate(any())).thenReturn(rawDraft);
        when(validator.validateAndSanitize(any(), any(), any()))
                .thenThrow(new beyou.beyouapp.backend.exceptions.BusinessException(
                        ErrorKey.AI_RESPONSE_INVALID, "still broken"));

        assertThrows(beyou.beyouapp.backend.exceptions.BusinessException.class,
                () -> service.generate(request, user));
        verify(generator, times(2)).generate(any());
    }
}
