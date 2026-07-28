package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R21 — the ORDER in which account deletion does its work.
 *
 * Attachment bytes are the one thing no foreign key reaches, so they have to be
 * removed by hand. Doing that before the row delete means a delete that fails
 * leaves the account standing with its screenshots already destroyed, and no
 * recovery. The delete CAN fail: {@code refresh_tokens.user_id} is a plain
 * non-cascading foreign key (V1__baseline.sql) and {@code User} maps no
 * {@code @OneToMany} for it, so every account that has ever logged in holds a
 * row that blocks the delete until it is cleared.
 *
 * These tests pin the order: clear the blocking rows, delete, and only then
 * touch the disk.
 */
@ExtendWith(MockitoExtension.class)
class UserDeletionOrderingUnitTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TokenService tokenService;

    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    PhotoStorageService photoStorageService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    FeedbackAttachmentService feedbackAttachmentService;

    private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private final User user = new User();
    private final List<UUID> submissionIds = List.of(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void setUp() {
        user.setId(userId);
        user.setName("deletion order");
        user.setEmail("deletion-order@beyou.test");
        userService = new UserService(userRepository, passwordEncoder, tokenService, refreshTokenService,
                new UserMapper(), photoStorageService, eventPublisher, feedbackAttachmentService);
    }

    @Test
    @DisplayName("the submission ids are captured, refresh tokens cleared, the row deleted, and only then are the files purged")
    void purgeHappensAfterTheRowDeleteSucceeds() {
        when(feedbackAttachmentService.findSubmissionIdsForUser(userId)).thenReturn(submissionIds);

        var response = userService.deleteUser(user);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        InOrder order = inOrder(feedbackAttachmentService, refreshTokenService, userRepository);
        // Ids first: they are addressed by feedback id, and the cascade takes those rows away.
        order.verify(feedbackAttachmentService).findSubmissionIdsForUser(userId);
        // The blocking rows next — nothing else clears them.
        order.verify(refreshTokenService).deleteAllForUser(userId);
        order.verify(userRepository).delete(user);
        // The delete has to have actually reached the database before the bytes go.
        order.verify(userRepository).flush();
        order.verify(feedbackAttachmentService).purgeStoredFiles(submissionIds);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("a delete blocked by a foreign key leaves the attachment files untouched")
    void blockedDeleteLeavesTheFilesIntact() {
        when(feedbackAttachmentService.findSubmissionIdsForUser(userId)).thenReturn(submissionIds);
        doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "update or delete on table \"users\" violates foreign key constraint on table \"refresh_tokens\""))
                .when(userRepository).flush();

        var response = userService.deleteUser(user);

        assertThat(response.getStatusCode().value())
                .as("a blocked delete must not report success")
                .isEqualTo(400);
        verify(feedbackAttachmentService, never()).purgeStoredFiles(anyCollection());
    }

    @Test
    @DisplayName("a delete that throws on the delete call itself leaves the attachment files untouched")
    void deleteThrowingLeavesTheFilesIntact() {
        when(feedbackAttachmentService.findSubmissionIdsForUser(userId)).thenReturn(submissionIds);
        doThrow(new IllegalStateException("boom")).when(userRepository).delete(any(User.class));

        var response = userService.deleteUser(user);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(feedbackAttachmentService, never()).purgeStoredFiles(anyCollection());
    }
}
