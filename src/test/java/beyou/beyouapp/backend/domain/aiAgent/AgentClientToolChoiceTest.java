package beyou.beyouapp.backend.domain.aiAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import reactor.core.publisher.Flux;

/**
 * What the agent's ChatClient puts on the wire for {@code ai.agent.tool-choice}.
 *
 * The setting is added to the client as a default option, and the ChatClient combines it
 * with the model's own options on every prompt. These tests read the prompt the model
 * receives, because that combination is where a value can be lost without any error: the
 * caller would believe it forced a tool call while the request said nothing.
 */
class AgentClientToolChoiceTest {

    private static final ChatResponse OK =
            new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

    private final ChatModel model = mock(ChatModel.class);

    private OpenAiChatOptions optionsSentWith(String toolChoice) {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("some-combo").build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(OK));
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("getUserGoals").description("reads goals").inputSchema("{}").build());

        ChatClient client = AiAgentService.agentClient(
                model, MessageWindowChatMemory.builder().build(), toolChoice);
        client.prompt()
                .user("update my goal")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "chat-1"))
                .tools(tool)
                .toolContext(Map.of("userId", "u1"))
                .stream()
                .content()
                .blockLast();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(sent.capture());
        return (OpenAiChatOptions) sent.getValue().getOptions();
    }

    @Test
    void configuredToolChoice_reachesTheModelAlongsideItsOwnOptions() {
        OpenAiChatOptions options = optionsSentWith("required");

        assertThat(options.getToolChoice()).isEqualTo("required");
        // The model id comes from LlmConfig. Adding tool_choice must leave it in place.
        assertThat(options.getModel()).isEqualTo("some-combo");
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolContext()).containsEntry("userId", "u1");
    }

    // Blank has to mean the field is absent, so the provider stays on its default (auto)
    // and turning the setting off is genuinely off.
    @Test
    void blankToolChoice_sendsNone() {
        OpenAiChatOptions options = optionsSentWith("");

        assertThat(options.getToolChoice()).isNull();
        assertThat(options.getModel()).isEqualTo("some-combo");
        assertThat(options.getToolCallbacks()).hasSize(1);
    }
}
