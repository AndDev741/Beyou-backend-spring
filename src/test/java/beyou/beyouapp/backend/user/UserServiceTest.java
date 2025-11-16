package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
public class UserServiceTest {
    @Mock
    HttpServletResponse response;

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TokenService tokenService;

    @InjectMocks
    private UserService userService;

    User user = new User();
    UUID userId = UUID.randomUUID();
    @BeforeEach
    void setUp(){
        SecurityContextHolder.clearContext();
        MockitoAnnotations.openMocks(this);

        user.setId(userId);
        user.setName("AndDev741");
        user.setEmail("myemail@gmail.com");
        user.setPerfilPhoto("url.com");
        user.setPerfilPhrase("life is good");
        user.setPerfilPhraseAuthor("lg?");
        user.setWidgetsIdInUse(List.of("widget4, widget5"));
    }

    @Test
    public void shouldReturnAuthenticatedIfUserAreAuthenticated(){
        ResponseEntity<String> response = userService.verifyAuthentication();

        assertEquals(response.getBody(), "authenticated");
    }

    @Test
    public void shouldGetAUserCorrectly(){
        String email = "testebeyou@gmail.com";
        Optional<User> getUser = userService.getUser(email);

        if(getUser.isPresent()){
            User user = getUser.get();
            assertEquals("341627d3-bae7-4c14-871b-d876413e8a0a", user.getId().toString());
            assertEquals("aaa", user.getName());
            assertEquals("testebeyou@gmail.com", user.getEmail());
            assertEquals(false, user.isGoogleAccount());
        }

    }

    @Test
    @Transactional
    public void shouldRegisterANewUser(){
        UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "email1234@gmail.com",
                "1234567", false);
        ResponseEntity<Map<String, String>> response = userService.registerUser(userRegisterDTO);

        assertEquals(ResponseEntity.ok().body(Map.of("success", "User registered successfully")),
                response);
    }

    @Test
    public void shouldMakeLoginSuccessfully() throws Exception {
        UserLoginDTO userLoginDTO = new UserLoginDTO("testebeyou@gmail.com", "123456");
        User user = new User();
        user.setPassword("hashedPassword");

        when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())).thenReturn(true);
        when(tokenService.generateToken(user)).thenReturn("mockedToken");


        ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(response, userLoginDTO);

        UserResponseDTO userResponseDTO = new UserResponseDTO(user.getName(),
                user.getEmail(), user.getPerfilPhrase(), user.getPerfilPhraseAuthor(),
                user.getConstance(), user.getPerfilPhoto(), user.isGoogleAccount(), user.getWidgetsIdInUse(), user.getThemeInUse());

        assertEquals(ResponseEntity.ok().body(Map.of("success", userResponseDTO)) ,loginResponse);
    }

    @Test
    public void shouldDeleteSuccessfullyAUser(){
        UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "newUser@gmail.com",
                "1234567", false);
        userService.registerUser(userRegisterDTO);
        Optional<User> newUser = userService.getUser(userRegisterDTO.email());

        if(newUser.isPresent()){
            ResponseEntity<Map<String, String>> response = userService.deleteUser(newUser.get());
            assertEquals(ResponseEntity.ok(Map.of("success", "User deleted successfully")),
                    response);
        }
    }

    @Test
    public void shouldEditTheUserInfoSuccessfully() {
        //Arrange
        UserEditDTO userEditDTO = new UserEditDTO("new Name", "newphoto.com", "new PHRASE", "phrase author", List.of(), "light");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        //ACT
        User editedUser = userService.editUser(userEditDTO, userId);

        //Assert
        assertEquals(editedUser.getName(), userEditDTO.name());
        assertEquals(editedUser.getPerfilPhoto(), userEditDTO.photo());
        assertEquals(editedUser.getPerfilPhrase(), userEditDTO.phrase());
        assertEquals(editedUser.getPerfilPhraseAuthor(), userEditDTO.phrase_author());
    }

    @Test
    public void shouldEditTheWidgetsSuccessfully() {
        //Arrange
        UserEditDTO userEditDTO = new UserEditDTO(null, null, null, null, List.of("widget1E, widget2E"), null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        //ACT

        User editedUser = userService.editWidgets(userEditDTO.widgetsId(), userId);
       
        //Assert
        assertEquals(editedUser.getWidgetsIdInUse(), userEditDTO.widgetsId());

    }

    //Exceptions in UserService

    @Test
    public void shouldThrowEmailAlreadyInUseError(){
        UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "email@gmail.com",
                "1234567", false);
        User user = new User(userRegisterDTO);
        when(userRepository.findByEmail(userRegisterDTO.email())).thenReturn(Optional.of(user));

        ResponseEntity<Map<String, String>> response = userService.registerUser(userRegisterDTO);

        assertEquals(ResponseEntity.badRequest().body(Map.of("error", "Email already in use")),
                response);
    }

    @Test
    public void shouldThrowExceptionForRequiredName(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            UserRegisterDTO newUser = new UserRegisterDTO("     ", "email@gmail.com",
                    "1234567", false);
            userService.registerUser(newUser);
        });

        assertEquals("Name is Required", exception.getMessage());
    }

    @Test
    public void shouldThrowExceptionForMinimumCharactersInName(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            UserRegisterDTO newUser = new UserRegisterDTO("N", "email@gmail.com",
                    "1234567", true);
            userService.registerUser(newUser);
        });

        assertEquals("Name require a minimum of 2 characters", exception.getMessage());
    }

    @Test
    public void shouldThrowExceptionForRequiredEmail(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
           UserRegisterDTO newUser = new UserRegisterDTO("Name", "",
                   "12345678", true);
           userService.registerUser(newUser);
        });

        assertEquals("Email is Required", exception.getMessage());
    }


    @Test
    public void shouldThrowExceptionForInvalidEmail(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            UserRegisterDTO newUser = new UserRegisterDTO("Name", "email",
                    "1234567", false);
            userService.registerUser(newUser);
        });

        assertEquals("Email is invalid", exception.getMessage());
    }

    @Test
    public void shouldThrowExceptionForRequiredPassword(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            UserRegisterDTO newUser = new UserRegisterDTO("Name", "email@gmail.com",
                    "           ", false);
            userService.registerUser(newUser);
        });

        assertEquals("Password is Required", exception.getMessage());
    }

    @Test
    public void shouldThrowExceptionForMinimumCharacterInPassword(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            UserRegisterDTO newUser = new UserRegisterDTO("Name", "email@gmail.com",
                    "12345", false);
            userService.registerUser(newUser);
        });

        assertEquals("Password require a minimum of 6 characters", exception.getMessage());
    }

    @Test
    public void shouldReturnIncorrectEmailOrPasswordByPassingWrongEmail() throws Exception {
        UserLoginDTO userLoginDTO = new UserLoginDTO("incorrect@gmail.com", "123456");

        when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(response, userLoginDTO);

        assertEquals(ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "Email or password incorrect")) ,loginResponse);
    }

    @Test
    public void shouldReturnIncorrectEmailOrPasswordByPassingWrongPassword() throws Exception {
        UserLoginDTO userLoginDTO = new UserLoginDTO("testebeyou@gmail.com", "313213213");
        User user = new User();
        user.setPassword("hashedPassword");

        when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.empty());
        when(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())).thenReturn(false);

        ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(response, userLoginDTO);

        assertEquals(ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "Email or password incorrect")) ,loginResponse);
    }
}
