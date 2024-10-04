package beyou.beyouapp.backend.user.dto;

public record GoogleUserDTO(String email, String name, String perfilPhoto) {
    public boolean isGoogleAccount() {
        return true;
    }
}
