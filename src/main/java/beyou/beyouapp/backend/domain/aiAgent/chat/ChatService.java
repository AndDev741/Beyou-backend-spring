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

    /** Bump updatedAt so the chat list stays ordered by recent activity. */
    public void touch(Chat chat) {
        chat.setUpdatedAt(java.time.LocalDateTime.now());
        chatRepository.save(chat);
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
