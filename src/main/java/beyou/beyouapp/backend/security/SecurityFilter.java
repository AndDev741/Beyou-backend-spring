package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.exceptions.ApiErrorResponse;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("null")
    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();

        if(
            requestURI.equals("/auth/login") ||
            requestURI.equals("/auth/register") ||
            requestURI.startsWith("/auth/refresh")||
            requestURI.startsWith("/auth/google") ||
            requestURI.startsWith("/auth/logout") ||
            requestURI.equals("/auth/forgot-password") ||
            requestURI.startsWith("/auth/reset-password") ||
            requestURI.equals("/auth/verify-email") ||
            (requestURI.startsWith("/docs") && !requestURI.startsWith("/docs/admin"))
        ){
            filterChain.doFilter(request, response);
            return;
        }

        try{
            String authorizationHeader = recoverTokenFromHeader(request);
            
            if(authorizationHeader != null){
                if(authorizationHeader.length() < 7 || !authorizationHeader.startsWith("Bearer")){
                    setResponseAsUnatuhorized(response, ErrorKey.AUTH_HEADER_INVALID, "Invalid Authorization header");
                    return;
                }

                String token = authorizationHeader.substring(7).trim();
                ResponseEntity<String> tokenValidationResponse = tokenService.validateToken(token);

                if(tokenValidationResponse.getStatusCode().is4xxClientError()){
                    setResponseAsUnatuhorized(response, ErrorKey.JWT_INVALID, "Invalid JWT Token");
                    return;
                }
                
                String userEmail = tokenValidationResponse.getBody();
                Optional<User> userOptional = userRepository.findByEmail(userEmail);

                if(userOptional.isPresent()){
                    User user = userOptional.get();
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }else{
                    setResponseAsUnatuhorized(response, ErrorKey.USER_NOT_FOUND, "User not found for the provided JWT");
                    return;
                }
            }
            filterChain.doFilter(request, response);

        }catch(JwtNotFoundException e){
            setResponseAsUnatuhorized(response, ErrorKey.JWT_NOT_FOUND, e.getMessage());
            return;
        }
    }

    private void setResponseAsUnatuhorized(HttpServletResponse response, ErrorKey errorKey, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(errorKey.name(), message, null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
        SecurityContextHolder.clearContext();
    }

    public String recoverTokenFromHeader(HttpServletRequest request){
        return Optional.ofNullable(request.getHeader("authorization"))
            .orElseThrow(() -> new JwtNotFoundException("JWT not Found in authorization header"));
    }

}
