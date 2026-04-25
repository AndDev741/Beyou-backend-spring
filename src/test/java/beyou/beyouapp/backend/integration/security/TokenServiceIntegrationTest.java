package beyou.beyouapp.backend.integration.security;

import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import beyou.beyouapp.backend.AbstractIntegrationTest;

import static org.junit.jupiter.api.Assertions.*;

public class TokenServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private TokenService tokenService;

    @Test
    public void shouldReturnAJWTToken(){
        User user= new User();
        user.setEmail("email@test.com");

        String token = tokenService.generateJwtToken(user);

        assertNotNull(token);
        assertFalse(token.isEmpty());

        String subject = tokenService.validateToken(token).getBody();
        assertEquals(user.getEmail(), subject);
    }

    @Test
    public void shouldValidateAToken(){
        User user = new User();
        user.setEmail("email@gmail.com");

        String token = tokenService.generateJwtToken(user);
        ResponseEntity<String> response = tokenService.validateToken(token);

        assertEquals(ResponseEntity.ok(user.getEmail()), response);
    }

    //Exceptions

    @Test
    public void shouldThrowAExceptionOfInvalidToken(){
        ResponseEntity<String> validation = tokenService.validateToken("ajsndajsdajsdja2321");

        assertEquals(ResponseEntity.badRequest().body("Invalid JWT Token"), validation);
    }

}
