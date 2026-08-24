package beyou.beyouapp.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferences;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesRepository;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The rules the engagement-mail switch has to hold, against a real database because
 * every one of them is about a row that may or may not exist.
 */
@Transactional
class NotificationPreferencesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    NotificationPreferencesService service;

    @Autowired
    NotificationPreferencesRepository repository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Preferences Owner");
        user.setEmail("prefs-" + UUID.randomUUID() + "@test.com");
        user.setPassword("irrelevant-for-this-test");
        user = userRepository.save(user);
    }

    /**
     * V24 writes no rows and backfills nothing, so every account that existed before it
     * has none. If absence read as "opted out", the feature would ship switched off for
     * the entire existing user base and look like a bug in the sender.
     */
    @Test
    @DisplayName("an account with no row is opted in, and gets a token minted on first read")
    void defaultsToOptedInAndMintsAToken() {
        assertThat(repository.findById(user.getId())).isEmpty();

        NotificationPreferences preferences = service.getOrCreate(user);

        assertThat(preferences.isEngagementEmail())
                .as("no stored preference means opted in, not opted out")
                .isTrue();
        assertThat(preferences.getUnsubscribeToken())
                .as("a mail cannot be sent before there is a token for it to link to")
                .isNotBlank();
    }

    /**
     * The load-bearing property of this token: it is STABLE. Every nudge ever sent links
     * to it, so a second read that minted a fresh one would silently kill the unsubscribe
     * link in every message already sitting in an inbox.
     */
    @Test
    @DisplayName("reading twice keeps the same token")
    void doesNotRotateTheTokenOnEveryRead() {
        String first = service.getOrCreate(user).getUnsubscribeToken();
        String second = service.getOrCreate(user).getUnsubscribeToken();

        assertThat(second).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("two accounts never share a token")
    void mintsADistinctTokenPerAccount() {
        User other = new User();
        other.setName("Someone Else");
        other.setEmail("prefs-" + UUID.randomUUID() + "@test.com");
        other.setPassword("irrelevant-for-this-test");
        other = userRepository.save(other);

        assertThat(service.getOrCreate(user).getUnsubscribeToken())
                .isNotEqualTo(service.getOrCreate(other).getUnsubscribeToken());
    }

    @Test
    @DisplayName("the token turns engagement mail off")
    void unsubscribeByTokenOptsOut() {
        String token = service.getOrCreate(user).getUnsubscribeToken();

        assertThat(service.unsubscribeByToken(token)).isTrue();
        assertThat(service.getOrCreate(user).isEngagementEmail()).isFalse();
    }

    /**
     * Mail clients prefetch links and people click twice. Neither may produce an error,
     * and neither may re-enable anything.
     */
    @Test
    @DisplayName("unsubscribing twice is the same as unsubscribing once")
    void unsubscribeIsIdempotent() {
        String token = service.getOrCreate(user).getUnsubscribeToken();

        assertThat(service.unsubscribeByToken(token)).isTrue();
        assertThat(service.unsubscribeByToken(token)).isTrue();
        assertThat(service.getOrCreate(user).isEngagementEmail()).isFalse();
    }

    @Test
    @DisplayName("a token nobody holds changes nothing")
    void unknownTokenIsRefused() {
        service.getOrCreate(user);

        assertThat(service.unsubscribeByToken("not-a-real-token")).isFalse();
        assertThat(service.unsubscribeByToken(null)).isFalse();
        assertThat(service.unsubscribeByToken("  ")).isFalse();
        assertThat(service.getOrCreate(user).isEngagementEmail())
                .as("a failed unsubscribe must not touch anyone's preference")
                .isTrue();
    }

    /**
     * The FK CASCADEs, unlike password_reset_tokens' plain one, which `UserService.
     * deleteUser` has to clear by hand before it can delete anything. If this table ever
     * grows a plain foreign key it would block account deletion outright — the one
     * action in the product with no undo — and the failure would surface as a 500 on
     * somebody trying to leave.
     */
    @Test
    @DisplayName("deleting the account takes its preferences with it, and is not blocked by them")
    void cascadesOnAccountDeletion() {
        service.getOrCreate(user);
        entityManager.flush();
        // Cleared on purpose. With the child still managed, `userRepository.delete` makes
        // Hibernate reorder the statements in-session and complain about an association
        // to a removed entity — which would be a test of Hibernate's cascade, not of the
        // one this asserts. The row delete below goes straight at the table, so what
        // answers it is Postgres applying the constraint from V24.
        entityManager.clear();

        entityManager.createNativeQuery("DELETE FROM users WHERE id = ?1")
                .setParameter(1, user.getId())
                .executeUpdate();

        assertThat(repository.findById(user.getId()))
                .as("a preference has no meaning once the account is gone")
                .isEmpty();
    }

    /**
     * Turning the switch back on is the whole reason the opt-out is safe to default on.
     */
    @Test
    @DisplayName("the switch goes both ways")
    void canOptBackIn() {
        service.setEngagementEmail(user, false);
        assertThat(service.getOrCreate(user).isEngagementEmail()).isFalse();

        service.setEngagementEmail(user, true);
        assertThat(service.getOrCreate(user).isEngagementEmail()).isTrue();
    }
}
