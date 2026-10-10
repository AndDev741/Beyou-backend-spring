package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserLanguage;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.federation.FederatedPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The language an account is born with, and the language its prompts are written in.
 *
 * <p>Before this, no signup path wrote {@code language_in_use}, so the column stayed empty
 * until someone opened the language setting. The daily briefing, the notebook tutor and the
 * verification mail all read that empty value as English, and a Portuguese user looking at a
 * Portuguese screen got English prose next to it.
 */
class SignupLanguageUnitTest {

    @Nested
    @DisplayName("UserLanguage")
    class Normalisation {

        @ParameterizedTest
        @CsvSource({"pt,pt", "pt-BR,pt", "PT_br,pt", "pt-PT,pt", "en,en", "en-GB,en", "' en-US ',en"})
        void mapsAClaimToTheLanguageTheAppShips(String claimed, String expected) {
            assertEquals(expected, UserLanguage.usableOrNull(claimed));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "fr", "es-ES", "portuguese", "-"})
        void dropsWhatItCannotUse(String claimed) {
            assertNull(UserLanguage.usableOrNull(claimed));
        }

        @Test
        @DisplayName("a blank saved language is missing, not a language")
        void blankIsMissingForPrompts() {
            // NotebookLlm used to pass "" straight to the model when the column was blank.
            User user = new User();
            user.setLanguageInUse("");

            assertEquals("en", UserLanguage.forPrompt(user));
        }

        @Test
        void promptsFollowTheSavedLanguage() {
            User user = new User();
            user.setLanguageInUse("pt");

            assertEquals("pt", UserLanguage.forPrompt(user));
        }
    }

    @Nested
    @DisplayName("signup")
    class Signup {

        @Test
        void emailRegisterAdoptsTheClaimedLanguage() {
            User user = new User(new UserRegisterDTO("Ana", "ana@example.com", "TestPassword1!", null, "pt-BR"));

            assertEquals("pt", user.getLanguageInUse());
        }

        @Test
        @DisplayName("a client that sends no language still registers, with the column empty")
        void emailRegisterWithoutAClaim() {
            User user = new User(new UserRegisterDTO("Ana", "ana@example.com", "TestPassword1!", null, null));

            assertNull(user.getLanguageInUse());
        }

        @Test
        @DisplayName("a language the app does not ship is dropped rather than stored")
        void emailRegisterDropsAnUnsupportedClaim() {
            User user = new User(new UserRegisterDTO("Ana", "ana@example.com", "TestPassword1!", null, "fr"));

            assertNull(user.getLanguageInUse());
        }

        @Test
        void googleSignInAdoptsTheClaimedLanguage() {
            User user = new User(new GoogleUserDTO("ana@example.com", "Ana", "http://pic").withLanguage("pt"));

            assertEquals("pt", user.getLanguageInUse());
        }

        @Test
        void federatedSignInAdoptsTheClaimedLanguage() {
            FederatedPrincipal principal = new FederatedPrincipal(
                    "https://issuer.example", "sub-1", "ana@example.com", true, "Ana", null)
                    .withClientClaims("Europe/Lisbon", "pt-PT");

            User user = User.fromFederatedPrincipal(principal);

            assertEquals("pt", user.getLanguageInUse());
            assertEquals("Europe/Lisbon", user.getTimezone());
        }
    }
}
