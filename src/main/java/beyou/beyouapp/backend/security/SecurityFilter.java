package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.exceptions.JwtCookieNotFoundException;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

@Component
public class SecurityFilter extends OncePerRequestFilter {
    @Autowired
    TokenService tokenService;

    @Autowired
    UserRepository userRepository;

    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();

        if(requestURI.equals("/auth/login") || requestURI.equals("/auth/register") ||
                requestURI.startsWith("/swagger-ui") || requestURI.startsWith("/v3/api-docs")
        || requestURI.startsWith("/auth/google")){
            filterChain.doFilter(request, response);
            return;
        }

        try{
            String token = recoverToken(request);
            if(token != null){
                ResponseEntity<String> tokenValidationResponse = tokenService.validateToken(token);

                if(tokenValidationResponse.getStatusCode().is4xxClientError()){
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("Invalid JWT Token");
                    response.getWriter().flush();
                    SecurityContextHolder.clearContext();
                    return;
                }
                String userEmail = tokenValidationResponse.getBody();
                Optional<User> userOptional = userRepository.findByEmail(userEmail);

                if(userOptional.isPresent()){
                    User user = userOptional.get();
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }else{
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("User not found");
                    response.getWriter().flush();
                    SecurityContextHolder.clearContext();
                    return;
                }
            }
            filterChain.doFilter(request, response);

        }catch(JwtCookieNotFoundException e){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(e.getMessage());
            response.getWriter().flush();
            SecurityContextHolder.clearContext();
        }
    }

    public String recoverToken(HttpServletRequest request){
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(cookie -> "jwt".equals(cookie.getName()))
                        .findFirst())
                .map(Cookie::getValue)
                .orElseThrow(() -> new JwtCookieNotFoundException("JWT Cookie not Found"));
    }
}
