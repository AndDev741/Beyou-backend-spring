package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.UserServiceGoogleOAuth;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final UserService userService;
    private final UserServiceGoogleOAuth userServiceGoogleOAuth;
    private final RefreshTokenService refreshTokenService;

    @GetMapping("/verify")
    public ResponseEntity<String> verifyAuthentication(){
        return userService.verifyAuthentication();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> doLogin(HttpServletResponse response, @RequestBody @Valid UserLoginDTO userLoginDTO){
        return userService.doLogin(response, userLoginDTO);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> doRegister(@RequestBody @Valid UserRegisterDTO userRegisterDTO){
        return userService.registerUser(userRegisterDTO);
    }

    @GetMapping("/google")
    public ResponseEntity<Map<String, Object>> googleAuth(@RequestParam("code") String code,
                                HttpServletResponse response){
        return userServiceGoogleOAuth.googleAuth(code, response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<String> refreshAccess(HttpServletRequest request, HttpServletResponse response){
        refreshTokenService.refreshAccessToken(request, response);
        return ResponseEntity.ok("Access Token refreshed");
    }
}


