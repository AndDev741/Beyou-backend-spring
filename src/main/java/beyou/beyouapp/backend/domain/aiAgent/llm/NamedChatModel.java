package beyou.beyouapp.backend.domain.aiAgent.llm;

import org.springframework.ai.chat.model.ChatModel;

/** A chain link: provider name (metrics/log label) + the model that serves it. */
public record NamedChatModel(String name, ChatModel model) {
}
