package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    public Optional<User> getUser(String email){
        try{
            return userRepository.findByEmail(email);
        }catch(Exception e){
            return Optional.empty();
        }
    }

    public ResponseEntity<Map<String, String>> doLogin(HttpServletResponse response, UserLoginDTO userLoginDTO){
        Optional<User> loginUser = userRepository.findByEmail(userLoginDTO.email());
        if(loginUser.isPresent()){
            User user = loginUser.get();
            if(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())){
                String token = tokenService.generateToken(user);
                addJwtTokenToResponse(response, token);
                return ResponseEntity.ok().body(Map.of("success", "User logged successfully"));
            }
        }
        return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "Email or password incorrect"));
    }

    public ResponseEntity<Map<String, String>> registerUser(UserRegisterDTO userRegisterDTO){
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();

        Set<ConstraintViolation<UserRegisterDTO>> violations = validator.validate(userRegisterDTO);

        if(!violations.isEmpty()) {
            for (ConstraintViolation<UserRegisterDTO> violation : violations){
                throw new IllegalArgumentException(violation.getMessage());
            }
        }

        Optional<User> verifyUser = userRepository.findByEmail(userRegisterDTO.email());
        if(verifyUser.isPresent()){
            return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));
        }else{
            User newUser = new User(userRegisterDTO);
            newUser.setPassword(passwordEncoder.encode(userRegisterDTO.password()));
            userRepository.save(newUser);
            return ResponseEntity.ok().body(Map.of("success", "User registered successfully"));
        }
    }

    public ResponseEntity<Map<String, String>> deleteUser(User user){
        try{
            userRepository.delete(user);
            return ResponseEntity.ok(Map.of("success", "User deleted successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private void addJwtTokenToResponse(HttpServletResponse response, String token){
        Cookie cookie = new Cookie("jwt", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(7 * 24 * 60 * 60);

        response.addCookie(cookie);

        response.setHeader(HttpHeaders.SET_COOKIE, "jwt=" + token + "; Max-Age=604800; Path=/; Secure; HttpOnly; SameSite=None");
    }
}
