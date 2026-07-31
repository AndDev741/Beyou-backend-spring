package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R21 — the ORDER in which account deletion does its work, and the fact that the
 * disk is touched from the COMMIT callback rather than inline.
 *
 * Attachment bytes are the one thing no foreign key reaches, so they have to be
 * removed by hand. Doing that before the commit means a delete that is rolled
 * back leaves the account standing with its screenshots already destroyed, and
 * no recovery. The delete CAN fail: {@code refresh_tokens.user_id} is a plain
 * non-cascading foreign key (V1__baseline.sql) and {@code User} maps no
 * {@code @OneToMany} for it, so every account that has ever logged in holds a
 * row that blocks the delete until it is cleared.
 *
 * <p><b>What this class can and cannot see.</b> It drives a hand-built
 * {@code UserService} with mocks: there is no transaction manager and no proxy.
 * So it pins the sequence of calls and the PHASE the purge is scheduled into —
 * by binding a synchronization context by hand and firing the commit callbacks
 * itself — and it deliberately makes no claim about the HTTP status a caller
 * receives, because the status is decided by the proxy this harness does not
 * have. {@code UserDeletionCommitBoundaryIntegrationTest} owns both of those:
 * the real commit boundary, and the fact that a blocked delete surfaces as
 * {@code UnexpectedRollbackException} (a 500) rather than the 400 the method's
 * own catch block builds.
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

    @AfterEach
    void clearAnyBoundSynchronization() {
        // A test that fails mid-transaction must not leave the thread-bound
        // synchronization behind for the next one.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the submission ids are captured, refresh tokens cleared, the row deleted — and the purge waits for the commit")
    void purgeIsDeferredUntilTheTransactionCommits() {
        when(feedbackAttachmentService.findSubmissionIdsForUser(userId)).thenReturn(submissionIds);

        TransactionSynchronizationManager.initSynchronization();

        var response = userService.deleteUser(user);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The method has returned and reported success, and the files are STILL
        // there. This is the assertion that fails if the purge moves back inline.
        verify(feedbackAttachmentService, never()).purgeStoredFiles(anyCollection());
        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .as("the purge has to be scheduled on the transaction, not performed on it")
                .hasSize(1);

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

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
    @DisplayName("a delete blocked by a foreign key schedules no purge, so a rollback destroys nothing")
    void blockedDeleteLeavesTheFilesIntact() {
        when(feedbackAttachmentService.findSubmissionIdsForUser(userId)).thenReturn(submissionIds);
        doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "update or delete on table \"users\" violates foreign key constraint on table \"refresh_tokens\""))
                .when(userRepository).flush();

        TransactionSynchronizationManager.initSynchronization();

        userService.deleteUser(user);

        // NO assertion on the returned status. The method builds a 400, but a
        // failed flush has already marked the real transaction rollback-only, so
        // the proxy raises UnexpectedRollbackException on the way out and the
        // client sees a 500 — a shape this proxy-less harness cannot produce and
        // therefore must not claim. Pinned for real in
        // UserDeletionCommitBoundaryIntegrationTest#blockedDeleteLeavesTheFilesOnDisk.
        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .as("nothing may be scheduled against a transaction that cannot commit")
                .isEmpty();

        // And the rollback that actually follows still touches no file.
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(feedbackAttachmentService, never()).purgeStoredFiles(anyCollection());
    }

    @Test
    @DisplayName("a delete that throws on the delete call itself leaves the attachment files untouched")
    void deleteThrowingLeavesTheFilesIntact() {
        when(feedbackAttachmentService.findSubmissionIdsForUser(userId)).thenReturn(submissionIds);
        doThrow(new IllegalStateException("boom")).when(userRepository).delete(any(User.class));

        TransactionSynchronizationManager.initSynchronization();

        var response = userService.deleteUser(user);

        // The 400 IS reachable here, unlike the flush case above: a plain runtime
        // exception from the repository leaves the transaction committable, so
        // the proxy commits an empty transaction and passes this response
        // through untouched.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(feedbackAttachmentService, never()).purgeStoredFiles(anyCollection());
    }

    @Test
    @DisplayName("with no transaction in progress the method deletes nothing at all rather than orphaning the files")
    void refusesToRunOutsideATransaction() {
        // No initSynchronization(): this is what a call with the Spring proxy
        // bypassed looks like. The row delete would auto-commit on its own and
        // there would be no commit callback to defer the purge to, so the only
        // safe answer is to do nothing.
        assertThatThrownBy(() -> userService.deleteUser(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must run inside a transaction");

        verifyNoInteractions(userRepository, refreshTokenService, feedbackAttachmentService);
    }
}
