package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
public class UserServiceUnitTest {
    
    @Mock
    HttpServletResponse response;

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TokenService tokenService;

    @Mock
    RefreshTokenService refreshTokenService;

    UserMapper userMapper = new UserMapper();

    private UserService userService;

    User user = new User();
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        MockitoAnnotations.openMocks(this);

        user.setId(userId);
        user.setName("AndDev741");
        user.setEmail("myemail@gmail.com");
        user.setPerfilPhoto("url.com");
        user.setPerfilPhrase("life is good");
        user.setPerfilPhraseAuthor("lg?");
        user.setWidgetsIdInUse(List.of("widget4, widget5"));

        userService = new UserService(userRepository, passwordEncoder, tokenService, refreshTokenService, userMapper);
    }

    @Nested
    class AuthenticateTest {
        @Test
        public void shouldReturnAuthenticatedIfUserAreAuthenticated() {
            ResponseEntity<String> response = userService.verifyAuthentication();

            assertEquals(response.getBody(), "authenticated");
        }
    }

    @Nested
    class LoginAndRegister {
        @Test
        public void shouldRegisterANewUser() {
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
            when(tokenService.generateJwtToken(user)).thenReturn("mockedToken");

            ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(response, userLoginDTO);

            UserResponseDTO userResponseDTO = userMapper.toResponseDTO(user);

            assertEquals(ResponseEntity.ok().body(Map.of("success", userResponseDTO)), loginResponse);
        }
    }

    @Nested
    class CrudOperations {
        @Test
        public void shouldGetAUserCorrectly() {
            String email = "testebeyou@gmail.com";
            Optional<User> getUser = userService.getUser(email);

            if (getUser.isPresent()) {
                User user = getUser.get();
                assertEquals("341627d3-bae7-4c14-871b-d876413e8a0a", user.getId().toString());
                assertEquals("aaa", user.getName());
                assertEquals("testebeyou@gmail.com", user.getEmail());
                assertEquals(false, user.isGoogleAccount());
            }

        }

        @Test
        public void shouldDeleteSuccessfullyAUser() {
            UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "newUser@gmail.com",
                    "1234567", false);
            userService.registerUser(userRegisterDTO);
            Optional<User> newUser = userService.getUser(userRegisterDTO.email());

            if (newUser.isPresent()) {
                ResponseEntity<Map<String, String>> response = userService.deleteUser(newUser.get());
                assertEquals(ResponseEntity.ok(Map.of("success", "User deleted successfully")),
                        response);
            }
        }

        @Test
        public void shouldEditTheUserInfoSuccessfully() {
            // Arrange
            UserEditDTO userEditDTO = new UserEditDTO(
                "new Name", 
                "newphoto.com", 
                "new PHRASE", 
                "phrase author",
                List.of(),
                "light",
                ConstanceConfiguration.ANY,
                "en",
                null
                );

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(user)).thenReturn(user);
            // ACT
            UserResponseDTO editedUser = userService.editUser(userEditDTO, userId);

            // Assert
            assertEquals(editedUser.name(), userEditDTO.name());
            assertEquals(editedUser.photo(), userEditDTO.photo());
            assertEquals(editedUser.phrase(), userEditDTO.phrase());
            assertEquals(editedUser.phrase_author(), userEditDTO.phrase_author());
        }

        @Test
        public void shouldEditTheWidgetsSuccessfully() {
            // Arrange
            UserEditDTO userEditDTO = new UserEditDTO(
                null, 
                null, 
                null, 
                null, 
                List.of("widget1E, widget2E"), 
                null,
                ConstanceConfiguration.ANY,
                "en",
                null
            );

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(user)).thenReturn(user);
            // ACT

            UserResponseDTO editedUser = userService.editUser(userEditDTO, userId);

            // Assert
            assertEquals(editedUser.widgetsId(), userEditDTO.widgetsId());

        }
    }

    @Nested
    class ConstanceLogic {
        @Test
        public void shouldGetCurrentConstanceByCompletedDates() {
            //Arrange
            Set<LocalDate> completedDates = new HashSet<>();
            LocalDate newDate = LocalDate.of(2026, 1, 18);
            completedDates.add(newDate);
            user.setCompletedDays(completedDates);
            
            //Act
            when(userRepository.save(user)).thenReturn(user);
            userService.markDayCompleted(user, newDate);

            //Assert
            verify(userRepository, times(1)).save(user);
            assertEquals(user.getCompletedDays().contains(newDate), true);
            assertEquals(user.getCurrentConstance( LocalDate.of(2026, 1, 18)), 1);
        }

        @Test
        public void shouldReturnTheCurrentConstanceByTheReferenceDate() {
            //Arrange
            Set<LocalDate> completedDates = new HashSet<>();
            completedDates.add(LocalDate.of(2026, 1, 17)); 
            completedDates.add(LocalDate.of(2026, 1, 18));
            completedDates.add(LocalDate.of(2026, 1, 19));
            user.setCompletedDays(completedDates);

            LocalDate dateToRemove = LocalDate.of(2026, 1, 18);
            //Act
            when(userRepository.save(user)).thenReturn(user);
            userService.unmarkDayComplete(user, dateToRemove);

            //Assert
            verify(userRepository, times(1)).save(user);
            assertEquals(user.getCompletedDays().contains(dateToRemove), false);
            assertEquals(user.getCurrentConstance(LocalDate.of(2026, 1, 19)), 1);
        }

        @Test
        public void shouldChangeTheMaxConstanceIfGreaterThanTheCurrentMaxConstance(){
            //Arrange
            Set<LocalDate> completedDates = new HashSet<>();
            completedDates.add(LocalDate.of(2026, 1, 17)); 
            completedDates.add(LocalDate.of(2026, 1, 18));
            completedDates.add(LocalDate.of(2026, 1, 19));
            user.setCompletedDays(completedDates);
            user.setMaxConstance(3);

            LocalDate dateToAdd = LocalDate.of(2026, 1, 20);
            //Act
            when(userRepository.save(user)).thenReturn(user);
            userService.markDayCompleted(user, dateToAdd);

            //Assert
            verify(userRepository, times(1)).save(user);
            assertEquals(user.getCurrentConstance(LocalDate.of(2026, 1, 20)), 4);
            assertEquals(user.getMaxConstance(), 4);
        }

        @Test
        public void shouldKeepStreakWhenUserDidNotCompleteTodayYet() {

            Set<LocalDate> completedDates = Set.of(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 1, 16),
                LocalDate.of(2026, 1, 17),
                LocalDate.of(2026, 1, 18)
            );

            user.setCompletedDays(completedDates);

            int streak = user.getCurrentConstance(LocalDate.of(2026, 1, 19));

            assertEquals(4, streak);
        }

        @Test
        public void shouldResetStreakIfUserSkippedOneFullDay() {

            Set<LocalDate> completedDates = Set.of(
                LocalDate.of(2026, 1, 16),
                LocalDate.of(2026, 1, 17)
            );

            user.setCompletedDays(completedDates);

            int streak = user.getCurrentConstance(LocalDate.of(2026, 1, 19));

            assertEquals(0, streak);
        }

        @Test
        public void shouldCalculateStreakCorrectlyWithUnorderedDates() {

            Set<LocalDate> completedDates = new HashSet<>();

            completedDates.add(LocalDate.of(2026, 1, 20));
            completedDates.add(LocalDate.of(2026, 1, 18));
            completedDates.add(LocalDate.of(2026, 1, 19));

            user.setCompletedDays(completedDates);

            int streak = user.getCurrentConstance(LocalDate.of(2026, 1, 20));

            assertEquals(3, streak);
        }

        @Test
        public void shouldRecalculateStreakAfterRemovingMiddleDay() {

            Set<LocalDate> completedDates = Set.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 3),
                LocalDate.of(2026, 1, 4),
                LocalDate.of(2026, 1, 5)
            );

            user.setCompletedDays(new HashSet<>(completedDates));

            userService.unmarkDayComplete(user, LocalDate.of(2026, 1, 3));

            int streak = user.getCurrentConstance(LocalDate.of(2026, 1, 5));

            assertEquals(2, streak);
        }

        @Test
        public void shouldReturnZeroWhenNoCompletedDaysExist() {

            user.setCompletedDays(new HashSet<>());

            int streak = user.getCurrentConstance(LocalDate.now());

            assertEquals(0, streak);
        }

        @Test
        public void markDayCompletedShouldNotManipulateStreakDirectly() {

            user.setCompletedDays(new HashSet<>());

            LocalDate today = LocalDate.of(2026, 1, 20);

            userService.markDayCompleted(user, today);

            assertTrue(user.getCompletedDays().contains(today));
            assertEquals(1, user.getCurrentConstance(today));
        }


    }

    @Nested
    class Exceptions {
        @Test
        public void shouldThrowEmailAlreadyInUseError() {
            UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "email@gmail.com",
                    "1234567", false);
            User user = new User(userRegisterDTO);
            when(userRepository.findByEmail(userRegisterDTO.email())).thenReturn(Optional.of(user));

            ResponseEntity<Map<String, String>> response = userService.registerUser(userRegisterDTO);

            assertEquals(ResponseEntity.badRequest().body(Map.of("error", "Email already in use")),
                    response);
        }

        @Test
        public void shouldThrowExceptionForRequiredName() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("     ", "email@gmail.com",
                        "1234567", false);
                userService.registerUser(newUser);
            });

            assertEquals("Name is Required", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForMinimumCharactersInName() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("N", "email@gmail.com",
                        "1234567", true);
                userService.registerUser(newUser);
            });

            assertEquals("Name require a minimum of 2 characters", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForRequiredEmail() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "",
                        "12345678", true);
                userService.registerUser(newUser);
            });

            assertEquals("Email is Required", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForInvalidEmail() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "email",
                        "1234567", false);
                userService.registerUser(newUser);
            });

            assertEquals("Email is invalid", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForRequiredPassword() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "email@gmail.com",
                        "           ", false);
                userService.registerUser(newUser);
            });

            assertEquals("Password is Required", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForMinimumCharacterInPassword() {
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

            assertEquals(ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "Email or password incorrect")), loginResponse);
        }

        @Test
        public void shouldReturnIncorrectEmailOrPasswordByPassingWrongPassword() throws Exception {
            UserLoginDTO userLoginDTO = new UserLoginDTO("testebeyou@gmail.com", "313213213");
            User user = new User();
            user.setPassword("hashedPassword");

            when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.empty());
            when(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())).thenReturn(false);

            ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(response, userLoginDTO);

            assertEquals(ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "Email or password incorrect")), loginResponse);
        }
    }
}
