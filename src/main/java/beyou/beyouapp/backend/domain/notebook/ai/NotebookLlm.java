package beyou.beyouapp.backend.domain.notebook.ai;

import java.time.LocalDate;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.extern.slf4j.Slf4j;

/**
 * Every model call the notebook makes goes through here: the fallback chain, one structured
 * answer, one retry asking for valid JSON, then {@link ErrorKey#AI_UNAVAILABLE}.
 *
 * <p>The same contract as the onboarding suggestions, on purpose: no tools, no streaming, no
 * memory. A notebook call is a question with its context attached, and the context (passages,
 * the chat so far) is assembled by the caller and sent in full every time.
 */
@Component
@Slf4j
public class NotebookLlm {

    private final ChatClient chatClient;
    private final Resource systemTemplate;

    public NotebookLlm(ChatModel chatModel,
            @Value("classpath:/prompts/notebookTutor.st") Resource systemTemplate) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemTemplate = systemTemplate;
    }

    public <T> T call(Class<T> type, String userMessage, User user) {
        try {
            return doCall(type, userMessage, user);
        } catch (RuntimeException first) {
            log.warn("Notebook AI call failed, retrying once: {}", first.getMessage());
            try {
                return doCall(type, userMessage + "\nIMPORTANT: return ONLY valid JSON matching the schema.", user);
            } catch (RuntimeException second) {
                log.error("Notebook AI retry failed", second);
                throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "The study AI is unavailable");
            }
        }
    }

    private <T> T doCall(Class<T> type, String userMessage, User user) {
        T result = chatClient.prompt()
                .system(s -> s.text(systemTemplate)
                        .param("language", user.getLanguageInUse() != null ? user.getLanguageInUse() : "en")
                        .param("today", LocalDate.now().toString()))
                .user(userMessage)
                .call()
                .entity(type);
        if (result == null) {
            throw new IllegalStateException("The model returned nothing");
        }
        return result;
    }
}
