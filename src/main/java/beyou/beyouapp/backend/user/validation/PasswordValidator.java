package beyou.beyouapp.backend.user.validation;

public class PasswordValidator {

    public static boolean isValid(String password) {
        if (password == null || password.length() < 12) return false;
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        return classes >= 2;
    }
}
