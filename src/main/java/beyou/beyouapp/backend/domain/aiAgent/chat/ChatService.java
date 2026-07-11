package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private static final String DEFAULT_TITLE = "New chat";
    public static final int GLOBAL_CONTEXT_MAX = 2000;
    public static final int CHAT_CONTEXT_MAX = 1000;

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final ChatMemory chatMemory;

    public Chat getChat(UUID chatId, UUID userId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new BusinessException(ErrorKey.CHAT_NOT_FOUND, "Chat not found"));
        if (!chat.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.CHAT_NOT_OWNED, "This chat does not belong to the user");
        }
        return chat;
    }

    public List<ChatResponseDTO> getAllChats(UUID userId) {
        return chatRepository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ChatResponseDTO::from)
                .toList();
    }

    public ChatResponseDTO createChat(String title, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to create a chat"));

        Chat chat = new Chat();
        chat.setUser(user);
        chat.setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : title.trim());
        return ChatResponseDTO.from(chatRepository.save(chat));
    }

    /**
     * Bump updatedAt so the chat list stays ordered by recent activity.
     * Reloads by id: the caller's Chat instance may be stale — a tool call
     * (updateChatContext) can have re-saved the row mid-request, and saving
     * the old detached entity would overwrite the fresh context.
     */
    public void touch(UUID chatId, UUID userId) {
        Chat chat = getChat(chatId, userId);
        chat.setUpdatedAt(java.time.LocalDateTime.now());
        chatRepository.save(chat);
    }

    /** Overwrites the user's cross-chat agent memory (clamped to {@value #GLOBAL_CONTEXT_MAX} chars). */
    public void updateGlobalContext(String context, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to update global context"));
        user.setUserContext(clamp(context, GLOBAL_CONTEXT_MAX));
        userRepository.save(user);
    }

    /** Overwrites this chat's agent memory (clamped to {@value #CHAT_CONTEXT_MAX} chars). */
    public void updateChatContext(String context, UUID chatId, UUID userId) {
        Chat chat = getChat(chatId, userId);
        chat.setUserContextInChat(clamp(context, CHAT_CONTEXT_MAX));
        chatRepository.save(chat);
    }

    private String clamp(String text, int max) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    @Transactional
    public void deleteChat(UUID chatId, UUID userId) {
        Chat chat = getChat(chatId, userId);
        try {
            chatRepository.delete(chat);
            chatMemory.clear(chatId.toString());
        } catch (Exception e) {
            log.error("Error trying to delete chat {}", chatId, e);
            throw new BusinessException(ErrorKey.CHAT_DELETE_FAILED, "Error trying to delete chat");
        }
    }
}
