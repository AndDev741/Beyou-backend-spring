package beyou.beyouapp.backend.exceptions;

public class JwtCookieNotFoundException extends RuntimeException {
    public JwtCookieNotFoundException(String message){
        super(message);
    }
}
