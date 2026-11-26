package beyou.beyouapp.backend.unit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import beyou.beyouapp.backend.security.aiconsent.ConsentCheckingChatModel;
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
    void everyCallOfTheAgentsToolLoopChecksFirst_soANoStopsTheNextOne() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse ok = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(model.call(any(Prompt.class))).thenReturn(ok);
        ConsentCheckingChatModel checked = new ConsentCheckingChatModel(model);
        Prompt prompt = new Prompt("hi", ToolCallingChatOptions.builder().toolContext(Map.of("userId", userId)).build());

        when(users.findAiConsent(userId)).thenReturn(AiConsent.GRANTED);
        assertThat(checked.call(prompt)).isSameAs(ok);

        // The person says no while a tool runs; the loop's next call to the model is refused.
        when(users.findAiConsent(userId)).thenReturn(AiConsent.DECLINED);
        assertThatThrownBy(() -> checked.call(prompt)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> checked.stream(prompt).blockLast()).isInstanceOf(BusinessException.class);
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void aPromptThatNamesNobodyIsRefused() {
        ChatModel model = mock(ChatModel.class);
        assertThatThrownBy(() -> new ConsentCheckingChatModel(model).call(new Prompt("hi")))
                .isInstanceOf(BusinessException.class);
        verify(model, times(0)).call(any(Prompt.class));
    }

    @Test
    void withNoPolicyEverythingIsRefused() {
        policy.unregister();
        assertThatThrownBy(() -> AiConsentPolicy.requireNow(userId)).isInstanceOf(BusinessException.class);
    }
}
