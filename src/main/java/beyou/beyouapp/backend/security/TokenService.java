package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.user.User;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class TokenService {
    @Value("${api.security.token.secret}")
    private String secret;

    public String generateToken(User  user){
        try{
            Algorithm algorithm = Algorithm.HMAC256(secret);
            String token = JWT.create()
                    .withIssuer("auth-api")
                    .withSubject(user.getEmail())
                    .withExpiresAt(genExpirationDate())
                    .sign(algorithm);
            return token;
        }catch (JWTCreationException exception){
            throw new RuntimeException("Error while generating token", exception);
        }

    }

    public ResponseEntity<String> validateToken(String token){
        try{
            Algorithm algorithm = Algorithm.HMAC256(secret);
            String validation = JWT.require(algorithm)
                    .withIssuer("auth-api")
                    .build()
                    .verify(token)
                    .getSubject();
            return ResponseEntity.ok(validation);
        }catch (JWTVerificationException exception){
            return ResponseEntity.badRequest().body("Invalid JWT Token");
        }
    }

    private Instant genExpirationDate(){
        return LocalDateTime.now().plusHours(1680).toInstant(ZoneOffset.of("-03:00"));
    }

}
