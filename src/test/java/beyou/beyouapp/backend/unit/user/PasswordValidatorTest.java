package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.user.validation.PasswordValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordValidatorTest {
    @Test
    void shouldRejectShortPasswords() {
        assertFalse(PasswordValidator.isValid("Abc1234567")); // 10 chars
    }
    @Test
    void shouldRejectSingleClassPasswords() {
        assertFalse(PasswordValidator.isValid("abcdefghijkl")); // only lowercase
    }
    @Test
    void shouldAcceptTwoClassPasswords() {
        assertTrue(PasswordValidator.isValid("abcdefghij1A")); // lower + upper + digit
    }
    @Test
    void shouldAcceptPasswordWithSpecialChars() {
        assertTrue(PasswordValidator.isValid("mypassword!1")); // lower + special + digit
    }
    @Test
    void shouldRejectNull() {
        assertFalse(PasswordValidator.isValid(null));
    }
    @Test
    void shouldAcceptExactly12CharsWithTwoClasses() {
        assertTrue(PasswordValidator.isValid("abcdefghijk1")); // 12 chars, lower + digit
    }
}
