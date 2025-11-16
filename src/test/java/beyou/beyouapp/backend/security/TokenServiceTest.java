package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class TokenServiceTest {
    @Autowired
    private TokenService tokenService;

    @Test
    public void shouldReturnAJWTToken(){
        User user= new User();
        user.setEmail("email@test.com");

        String token = tokenService.generateToken(user);

        assertNotNull(token);
        assertFalse(token.isEmpty());

        String subject = tokenService.validateToken(token).getBody();
        assertEquals(user.getEmail(), subject);
    }

    @Test
    public void shouldValidateAToken(){
        User user = new User();
        user.setEmail("email@gmail.com");

        String token = tokenService.generateToken(user);
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
