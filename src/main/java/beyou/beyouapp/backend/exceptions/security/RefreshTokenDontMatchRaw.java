package beyou.beyouapp.backend.exceptions.security;

public class RefreshTokenDontMatchRaw extends RuntimeException {
    public RefreshTokenDontMatchRaw(String message){
        super(message);
    }
}