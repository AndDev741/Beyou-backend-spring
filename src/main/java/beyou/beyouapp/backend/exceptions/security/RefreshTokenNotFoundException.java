package beyou.beyouapp.backend.exceptions.security;

public class RefreshTokenNotFoundException extends RuntimeException {
    public RefreshTokenNotFoundException(String message){
        super(message);
    }
}
