package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    public ResponseEntity<String> verifyAuthentication(){
        return ResponseEntity.ok().body("authenticated");
    }

    public Optional<User> getUser(String email){
        try{
            return userRepository.findByEmail(email);
        }catch(Exception e){
            return Optional.empty();
        }
    }

    public ResponseEntity<Map<String, Object>> doLogin(HttpServletResponse response, UserLoginDTO userLoginDTO){
        Optional<User> loginUser = userRepository.findByEmail(userLoginDTO.email());
        if(loginUser.isPresent()){
            User user = loginUser.get();
            if(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())){
                String token = tokenService.generateToken(user);
                addJwtTokenToResponse(response, token);
                UserResponseDTO userResponse = new UserResponseDTO(
                    user.getName(),
                    user.getEmail(), 
                    user.getPerfilPhrase(), 
                    user.getPerfilPhraseAuthor(),
                    user.getConstance(), 
                    user.getPerfilPhoto(), 
                    user.isGoogleAccount(), 
                    user.getWidgetsIdInUse(),
                    user.getThemeInUse(),
                    user.getXpProgress().getXp(),
                    user.getXpProgress().getActualLevelXp(),
                    user.getXpProgress().getNextLevelXp(),
                    user.getXpProgress().getLevel()
                );
                return ResponseEntity.ok().body(Map.of("success", userResponse));
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

    public User editUser(UserEditDTO userEdit, UUID userId){
        Optional<User> userOpt = userRepository.findById(userId);
        if(userOpt.isPresent()){
            User user = userOpt.get();
            user.setName(userEdit.name() != null ? userEdit.name() : user.getName());
            user.setPerfilPhoto(userEdit.photo() != null ? userEdit.photo() : user.getPerfilPhoto());
            user.setPerfilPhrase(userEdit.phrase() != null ? userEdit.phrase() : user.getPerfilPhrase());
            user.setPerfilPhraseAuthor(userEdit.phrase_author() != null ? userEdit.phrase_author() : user.getPerfilPhraseAuthor());
            user.setThemeInUse(userEdit.theme() != null ? userEdit.theme() : user.getThemeInUse());
            try{
                User saved = userRepository.save(user);
                return saved;
            }catch(Exception e){
               throw e;
            }
        }else{
            throw new UserNotFound("User not found by id");
        }
    }

    public User editWidgets(List<String> widgetsId, UUID userId){
        Optional<User> userOpt = userRepository.findById(userId);
        if(userOpt.isPresent()){
            User user = userOpt.get();
            user.setWidgetsIdInUse(widgetsId);
            try{
                User savedUser = userRepository.save(user);
                return savedUser;
            }catch(Exception e){
                throw e;
            }
        }else{
            throw new UserNotFound("User not found by id");
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
