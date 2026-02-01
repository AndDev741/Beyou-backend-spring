package beyou.beyouapp.backend.exceptions.security;

public class RefreshTokenExpiredException extends RuntimeException {
    public RefreshTokenExpiredException(String message){
        super(message);
    }
}
