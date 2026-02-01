package beyou.beyouapp.backend.exceptions.security;

public class JwtNotFoundException extends RuntimeException {
    public JwtNotFoundException(String message){
        super(message);
    }
}
