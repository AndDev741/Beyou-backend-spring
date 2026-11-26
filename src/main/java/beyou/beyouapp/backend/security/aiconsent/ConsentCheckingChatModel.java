package beyou.beyouapp.backend.security.aiconsent;

import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import reactor.core.publisher.Flux;

/**
 * The assistant's model, checking the person's yes to the AI provider right before every call:
 * the first one of a turn and each one the tool loop makes after a tool, a failed tool included.
 *
 * <p>The person comes from the prompt's tool context ({@code userId}), which the agent sets on
 * every turn and which Spring AI carries into each call of the loop. A prompt without one is
 * refused, like everything else without a yes ({@link AiConsentPolicy#requireNow}).
 */
public class ConsentCheckingChatModel implements ChatModel {

    private final ChatModel delegate;

    public ConsentCheckingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        AiConsentPolicy.requireNow(userIdOf(prompt));
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Deferred, so the answer is read when the call is made, not when the Flux is assembled.
        return Flux.defer(() -> {
            AiConsentPolicy.requireNow(userIdOf(prompt));
            return delegate.stream(prompt);
        });
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    static UUID userIdOf(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return null;
        }
        Map<String, Object> context = options.getToolContext();
        Object id = context == null ? null : context.get("userId");
        if (id instanceof UUID uuid) return uuid;
        if (id instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException notAnId) {
                return null;
            }
        }
        return null;
    }
}
