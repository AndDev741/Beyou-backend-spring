package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.TimezoneSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an account is born with.
 *
 * <p>Both {@code User} constructors are the funnel for all four signup paths (web register,
 * mobile register, Google web, Google mobile), which is why the adoption lives there rather
 * than in the four services: a path that forgets it creates an account running on the UTC
 * calendar wherever its owner actually is, and every date that account ever writes is
 * resolved against that.
 *
 * <p>The other half of the contract is that a bad claim is DROPPED, never fatal. These run
 * against the constructors directly, with no Spring and no database, because the rule is
 * about the entity and nothing else.
 */
class SignupTimezoneUnitTest {

    private static UserRegisterDTO register(String timezone) {
        return new UserRegisterDTO("Ana", "ana@example.com", "TestPassword1!", timezone);
    }

    private static GoogleUserDTO google(String timezone) {
        return new GoogleUserDTO("ana@example.com", "Ana", "http://pic", timezone);
    }

    @Nested
    @DisplayName("email/password register")
    class EmailRegister {

        @Test
        void adoptsTheClaimedZone() {
            User user = new User(register("Europe/Lisbon"));

            assertEquals("Europe/Lisbon", user.getTimezone());
            assertEquals(TimezoneSource.DETECTED, user.getTimezoneSource());
        }

        @Test
        void adoptsALargeOffsetZoneToo() {
            User user = new User(register("America/Sao_Paulo"));

            assertEquals("America/Sao_Paulo", user.getTimezone());
            assertEquals(TimezoneSource.DETECTED, user.getTimezoneSource());
        }

        @Test
        @DisplayName("a client that sends no zone still registers, on the old default")
        void nullClaimKeepsTheDefault() {
            User user = new User(register(null));

            assertEquals("UTC", user.getTimezone());
            assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
        }

        @Test
        @DisplayName("an unusable zone is dropped rather than refused")
        void unknownClaimIsDropped() {
            // The registration must not fail over a convenience field. The account lands on
            // the default as DEFAULT, so the boot reconcile gets another attempt at it.
            User user = new User(register("Mars/Olympus"));

            assertEquals("UTC", user.getTimezone());
            assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
        }

        @Test
        void blankClaimIsDropped() {
            User user = new User(register("   "));

            assertEquals("UTC", user.getTimezone());
            assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
        }

        @Test
        @DisplayName("a deliberate UTC claim is still only DETECTED, never EXPLICIT")
        void claimingUtcIsNotAPick() {
            // A device reporting UTC has not chosen anything. Stamping EXPLICIT here would
            // freeze the account against the very correction this whole change exists for.
            User user = new User(register("UTC"));

            assertEquals("UTC", user.getTimezone());
            assertEquals(TimezoneSource.DETECTED, user.getTimezoneSource());
        }
    }

    @Nested
    @DisplayName("Google sign-in, web and mobile")
    class GoogleSignIn {

        @Test
        void adoptsTheClaimedZone() {
            User user = new User(google("Europe/Lisbon"));

            assertEquals("Europe/Lisbon", user.getTimezone());
            assertEquals(TimezoneSource.DETECTED, user.getTimezoneSource());
            assertEquals(true, user.isGoogleAccount());
        }

        @Test
        void nullClaimKeepsTheDefault() {
            User user = new User(google(null));

            assertEquals("UTC", user.getTimezone());
            assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
        }

        @Test
        @DisplayName("the three-arg form still works, because the ID token has no zone claim")
        void threeArgFormIsZoneless() {
            // GoogleIdTokenVerifierServiceImpl builds the DTO from the verified token, which
            // carries no timezone. The mobile path merges the device's zone in afterwards.
            GoogleUserDTO fromVerifier = new GoogleUserDTO("ana@example.com", "Ana", "http://pic");

            assertEquals(null, fromVerifier.timezone());
            assertEquals("America/Sao_Paulo",
                    fromVerifier.withTimezone("America/Sao_Paulo").timezone());
            assertEquals("Ana", fromVerifier.withTimezone("America/Sao_Paulo").name());
        }

        @Test
        void unknownClaimIsDropped() {
            User user = new User(google("Mars/Olympus"));

            assertEquals("UTC", user.getTimezone());
            assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
        }
    }
}
