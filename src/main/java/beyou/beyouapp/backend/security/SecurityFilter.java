package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class SecurityFilter extends OncePerRequestFilter {
    @Autowired
    TokenService tokenService;

    @Autowired
    UserRepository userRepository;

    @SuppressWarnings("null")
    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();

        if(
            requestURI.equals("/auth/login") || 
            requestURI.equals("/auth/register") ||
            requestURI.startsWith("/auth/refresh")|| 
            requestURI.startsWith("/auth/google")
        ){
            filterChain.doFilter(request, response);
            return;
        }

        try{
            String authorizationHeader = recoverTokenFromHeader(request);
            
            if(authorizationHeader != null){
                if(authorizationHeader.length() < 7 || !authorizationHeader.startsWith("Bearer")){
                    setResponseAsUnatuhorized(response, "Invalid Authorization header");
                    return;
                }

                String token = authorizationHeader.substring(7).trim();
                ResponseEntity<String> tokenValidationResponse = tokenService.validateToken(token);

                if(tokenValidationResponse.getStatusCode().is4xxClientError()){
                    setResponseAsUnatuhorized(response, "Invalid JWT Token");
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

        }catch(JwtNotFoundException e){
            setResponseAsUnatuhorized(response, e.getMessage());
            return;
        }
    }

    private void setResponseAsUnatuhorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write(message);
        response.getWriter().flush();
        SecurityContextHolder.clearContext();
    }

    public String recoverTokenFromHeader(HttpServletRequest request){
        return Optional.ofNullable(request.getHeader("authorization"))
            .orElseThrow(() -> new JwtNotFoundException("JWT not Found in authorization header"));
    }

}
