package beyou.beyouapp.backend.unit.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.security.ratelimit.LoginAttemptService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cap on guessing at one account.
 *
 * <p>The audit found the login limiter leaning entirely on the caller's address, taken
 * from a header the caller could choose — so every request landed in a fresh bucket and
 * the 5-per-15-minutes cap never fired at all. Reading a header the edge sets fixes the
 * spoofing, but not the shape of the problem: behind a tunnel every request shares one
 * socket address, so an address bucket there either lets the whole internet through
 * together or locks it out together.
 *
 * <p>An account is the thing under attack, so an account is what gets counted. These
 * tests pin the three properties that make the counter worth having.
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service(int maxAttempts) {
        return new LoginAttemptService(maxAttempts, 15);
    }

    @Test
    @DisplayName("an account is free until it has spent its guesses")
    void unlockedUntilTheCapIsReached() {
        LoginAttemptService service = service(3);

        assertThat(service.isLocked("someone@beyou.test")).isFalse();
        service.recordFailure("someone@beyou.test");
        service.recordFailure("someone@beyou.test");
        assertThat(service.isLocked("someone@beyou.test")).isFalse();

        service.recordFailure("someone@beyou.test");
        assertThat(service.isLocked("someone@beyou.test")).isTrue();
    }

    /**
     * The point of counting the account rather than the caller: credential stuffing
     * spreads across addresses by design, and behind a tunnel there is only one address
     * to spread from anyway.
     */
    @Test
    @DisplayName("the count follows the account, not whoever is asking")
    void lockingOneAccountLeavesTheOthersAlone() {
        LoginAttemptService service = service(2);

        service.recordFailure("target@beyou.test");
        service.recordFailure("target@beyou.test");

        assertThat(service.isLocked("target@beyou.test")).isTrue();
        assertThat(service.isLocked("someone-else@beyou.test")).isFalse();
    }

    /** Addresses are not case sensitive, and neither is the attack. */
    @Test
    @DisplayName("case and padding do not buy extra guesses")
    void theKeyIsNormalised() {
        LoginAttemptService service = service(2);

        service.recordFailure("Target@Beyou.Test");
        service.recordFailure("  target@beyou.test  ");

        assertThat(service.isLocked("target@beyou.test")).isTrue();
    }

    /**
     * Someone who mistyped four times and then got it right should not carry the count
     * around until it ages out.
     */
    @Test
    @DisplayName("getting in clears the slate")
    void successForgetsTheFailures() {
        LoginAttemptService service = service(3);

        service.recordFailure("someone@beyou.test");
        service.recordFailure("someone@beyou.test");
        service.recordSuccess("someone@beyou.test");
        service.recordFailure("someone@beyou.test");

        assertThat(service.isLocked("someone@beyou.test")).isFalse();
    }

    /**
     * An unknown address is counted exactly like a known one. Skipping it would turn
     * the lockout into an enumeration oracle: guess past the cap and see whether the
     * answer changes, and you have learned which emails are real.
     */
    @Test
    @DisplayName("an address that does not exist locks like any other")
    void unknownAccountsAreCountedToo() {
        LoginAttemptService service = service(2);

        service.recordFailure("nobody-here@beyou.test");
        service.recordFailure("nobody-here@beyou.test");

        assertThat(service.isLocked("nobody-here@beyou.test")).isTrue();
    }

    @Test
    @DisplayName("a null address does not blow up the login path")
    void nullIsSurvivable() {
        LoginAttemptService service = service(2);

        org.assertj.core.api.Assertions
                .assertThatCode(() -> {
                    service.recordFailure(null);
                    service.isLocked(null);
                    service.recordSuccess(null);
                })
                .doesNotThrowAnyException();
    }
}
