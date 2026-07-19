package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

class LlmChainConfigTest {

    private final DeepSeekChatModel deepSeek = mock(DeepSeekChatModel.class);

    private ChatModel build(LlmChainProperties props) {
        return new LlmChainConfig().agentChatModel(
                props, deepSeek, ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    @Test
    void chainFollowsConfiguredOrder_andSkipsProvidersWithoutApiKey() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("groq", "gemini", "deepseek"), 300, 30,
                Map.of(
                        "groq", new LlmChainProperties.Provider(
                                "https://api.groq.com/openai/v1", "gk-123", "llama-3.3-70b-versatile"),
                        "gemini", new LlmChainProperties.Provider(
                                "https://generativelanguage.googleapis.com/v1beta/openai", "", "gemini-2.5-flash")));

        ChatModel chain = build(props);

        assertThat(chain).isInstanceOf(FallbackChatModel.class);
        assertThat(((FallbackChatModel) chain).providerNames()).containsExactly("groq", "deepseek");
    }

    @Test
    void unknownProviderNameInOrder_isSkippedNotFatal() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("typo-provider", "deepseek"), 300, 30, Map.of());

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }

    @Test
    void deepseekOnly_whenNoFreeProviderConfigured() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("groq", "gemini", "mistral", "cerebras", "deepseek"), 300, 30, Map.of());

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }
}
