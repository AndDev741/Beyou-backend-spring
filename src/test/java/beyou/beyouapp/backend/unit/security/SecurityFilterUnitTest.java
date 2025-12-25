package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.exceptions.JwtCookieNotFoundException;
import beyou.beyouapp.backend.security.SecurityFilter;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SecurityFilterUnitTest {
    @Mock
    private TokenService tokenService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FilterChain filterChain;

    @Mock
    private PrintWriter printWriter;

    @InjectMocks
    private SecurityFilter securityFilter;

    @Test
    public void shouldPassThroughTheFunctionSuccessfully() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/");
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("jwt", "valid")});
        when(tokenService.validateToken("valid")).thenReturn(ResponseEntity.ok("user@gmail.com"));
        when(userRepository.findByEmail("user@gmail.com")).thenReturn(Optional.of(new User()));

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain, atLeastOnce()).doFilter(request, response);

    }

    @Test
    public void shouldReturnTheJwtTokenSent(){
        User user = new User();
        user.setEmail("email@gmail.com");

        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("jwt", "validToken")});

        String recoveredToken = securityFilter.recoverToken(request);

        assertEquals("validToken", recoveredToken);
    }

    //Exceptions

    @Test
    public void shouldThrowInvalidTokenResponse() throws ServletException, IOException {
        when(response.getWriter()).thenReturn(printWriter);
        when(request.getRequestURI()).thenReturn("/");
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("jwt", "invalidToken")});
        when(tokenService.validateToken(anyString())).thenReturn(ResponseEntity.badRequest().body("Invalid JWT Token"));

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(printWriter).write("Invalid JWT Token");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    public void shouldThrowTheUserWasNotFound() throws IOException, ServletException {
        when(response.getWriter()).thenReturn(printWriter);
        when(request.getRequestURI()).thenReturn("/");
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("jwt", "validToken")});

        when(tokenService.validateToken(anyString())).thenReturn(ResponseEntity.ok("user@example.com"));

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(printWriter).write("User not found");
        verify(filterChain, never()).doFilter(request, response);

    }

    @Test
    public void shouldThrowTheJwtTokenWasNotFound(){
        User user = new User();
        user.setEmail("email@gmail.com");

        Exception exception = assertThrows(JwtCookieNotFoundException.class, () -> {
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("notValid", "notValidToken")});
            securityFilter.recoverToken(request);
        });

        assertEquals("JWT Cookie not Found", exception.getMessage());
    }

}
