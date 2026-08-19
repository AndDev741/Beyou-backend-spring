package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                List.of("groq", "gemini", "deepseek"), List.of(), 300, 30,
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
                List.of("typo-provider", "deepseek"), List.of(), 300, 30, Map.of());

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }

    @Test
    void deepseekOnly_whenNoFreeProviderConfigured() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("groq", "gemini", "mistral", "cerebras", "deepseek"), List.of(), 300, 30, Map.of());

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }

    /**
     * Production blocks the providers established where no EU adequacy decision
     * covers them. `order` is an environment variable, so the block has to win over
     * it — otherwise a hurried LLM_CHAIN_ORDER edit silently undoes the policy.
     */
    @Test
    void blockedProvider_isDroppedEvenWhenTheOrderAsksForIt() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("glm", "deepseek"), List.of("glm", "deepseek"), 300, 30,
                Map.of("glm", new LlmChainProperties.Provider(
                        "https://api.z.ai/api/paas/v4", "zk-123", "glm-4.7-flash")));

        // Nothing survives the blocklist here, and an empty chain must not boot quietly.
        assertThatThrownBy(() -> build(props)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockedProvider_matchIsCaseInsensitive() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("GLM", "deepseek"), List.of("glm"), 300, 30,
                Map.of("GLM", new LlmChainProperties.Provider(
                        "https://api.z.ai/api/paas/v4", "zk-123", "glm-4.7-flash")));

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }
}
