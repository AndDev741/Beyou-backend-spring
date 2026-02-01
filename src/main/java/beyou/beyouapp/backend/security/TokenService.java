package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

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

    public String generateJwtToken(User  user){
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

    public void addJwtTokenToResponse(HttpServletResponse response, String accessToken, String refreshToken){
        response.addHeader("accessToken", accessToken);

        Cookie cookie = new Cookie("refreshToken", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(15 * 24 * 60 * 60); //15 days

        response.addCookie(cookie);
    }

    private Instant genExpirationDate(){
        return LocalDateTime.now().plusMinutes(15).toInstant(ZoneOffset.of("-00:00")); //15 minutes
    }

}
