package beyou.beyouapp.backend.unit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import beyou.beyouapp.backend.domain.aiAgent.MeteredToolCallback;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.aiconsent.AiConsentPolicy;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.enums.AiConsent;

/**
 * A "no" given while work is under way stops the next call to the provider: the answer is read
 * fresh before each one, not once at the start.
 */
class AiConsentRevokedMidWorkTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AiConsentPolicy policy = new AiConsentPolicy(users);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void register() {
        policy.register();
    }

    @AfterEach
    void unregister() {
        policy.unregister();
    }

    @Test
    void theNextCallIsRefusedOnceTheAnswerTurnsToNo() {
        when(users.findAiConsent(userId)).thenReturn(AiConsent.GRANTED);
        AiConsentPolicy.requireNow(userId);

        when(users.findAiConsent(userId)).thenReturn(AiConsent.DECLINED);
        assertThatThrownBy(() -> AiConsentPolicy.requireNow(userId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.AI_CONSENT_DECLINED));
    }

    @Test
    void theAgentStopsAfterATool_insteadOfHandingTheResultBackToTheModel() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name("listHabits").description("d").inputSchema("{}").build());
        when(tool.call(anyString(), any(ToolContext.class))).thenReturn("[]");
        MeteredToolCallback metered = new MeteredToolCallback(tool, new SimpleMeterRegistry());
        ToolContext context = new ToolContext(Map.of("userId", userId));

        when(users.findAiConsent(userId)).thenReturn(AiConsent.GRANTED);
        assertThat(metered.call("{}", context)).isEqualTo("[]");

        when(users.findAiConsent(userId)).thenReturn(AiConsent.DECLINED);
        assertThatThrownBy(() -> metered.call("{}", context))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.AI_CONSENT_DECLINED));
    }
}
