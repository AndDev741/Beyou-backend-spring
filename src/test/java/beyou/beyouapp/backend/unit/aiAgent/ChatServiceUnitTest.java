package beyou.beyouapp.backend.unit.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;

import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatRepository;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
public class ChatServiceUnitTest {

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChatMemory chatMemory;

    private ChatService chatService;

    UUID chatId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user = new User();
    Chat chat = new Chat();

    @BeforeEach
    void setup() {
        user.setId(userId);

        chat.setId(chatId);
        chat.setTitle("My chat");
        chat.setUser(user);
        chat.setCreatedAt(LocalDateTime.now().minusDays(1));
        chat.setUpdatedAt(LocalDateTime.now().minusDays(1));

        chatService = new ChatService(chatRepository, userRepository, chatMemory);
    }

    @Test
    void shouldGetChatSuccessfully() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));

        Chat found = chatService.getChat(chatId, userId);

        assertEquals(chatId, found.getId());
    }

    @Test
    void shouldThrowNotFound_whenChatDoesNotExist() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.getChat(chatId, userId));

        assertEquals(ErrorKey.CHAT_NOT_FOUND, exception.getErrorKey());
    }

    @Test
    void shouldThrowNotOwned_whenChatBelongsToAnotherUser() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.getChat(chatId, UUID.randomUUID()));

        assertEquals(ErrorKey.CHAT_NOT_OWNED, exception.getErrorKey());
    }

    @Test
    void shouldGetAllChatsAsDTOs() {
        when(chatRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(chat));

        List<ChatResponseDTO> chats = chatService.getAllChats(userId);

        assertEquals(1, chats.size());
        assertEquals(chat.getTitle(), chats.get(0).title());
        assertEquals(chatId, chats.get(0).id());
    }

    @Test
    void shouldCreateChatWithTrimmedTitle() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatResponseDTO created = chatService.createChat("  Plan my week  ", userId);

        assertEquals("Plan my week", created.title());
    }

    @Test
    void shouldCreateChatWithDefaultTitle_whenTitleIsBlank() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("New chat", chatService.createChat("   ", userId).title());
        assertEquals("New chat", chatService.createChat(null, userId).title());
    }

    @Test
    void shouldThrowUserNotFound_whenCreatingChatForUnknownUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(UserNotFound.class, () -> chatService.createChat("Title", userId));
        verify(chatRepository, never()).save(any(Chat.class));
    }

    @Test
    void shouldBumpUpdatedAtOnTouch() {
        LocalDateTime before = chat.getUpdatedAt();

        chatService.touch(chat);

        assertNotNull(chat.getUpdatedAt());
        assertEquals(true, chat.getUpdatedAt().isAfter(before));
        verify(chatRepository).save(chat);
    }

    @Test
    void shouldDeleteChatAndClearMemory() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));

        chatService.deleteChat(chatId, userId);

        verify(chatRepository).delete(chat);
        verify(chatMemory).clear(chatId.toString());
    }

    @Test
    void shouldNotDeleteChatOfAnotherUser() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));

        assertThrows(BusinessException.class, () -> chatService.deleteChat(chatId, UUID.randomUUID()));

        verify(chatRepository, never()).delete(any(Chat.class));
        verify(chatMemory, never()).clear(any());
    }

    @Test
    void shouldThrowDeleteFailed_whenRepositoryFails() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        doThrow(new RuntimeException()).when(chatRepository).delete(chat);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.deleteChat(chatId, userId));

        assertEquals(ErrorKey.CHAT_DELETE_FAILED, exception.getErrorKey());
    }
}
