package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.UserServiceGoogleOAuth;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthenticationController {
    @Autowired
    private UserService userService;
    @Autowired
    private UserServiceGoogleOAuth userServiceGoogleOAuth;

    @GetMapping("/verify")
    public ResponseEntity<String> verifyAuthentication(){
        return userService.verifyAuthentication();
    }

    //Documentation
    @Operation(summary = "Realize the user login")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User logged successfully",
                    content = { @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Map.class))}),
            @ApiResponse(responseCode = "401", description = "Email or password incorrect",
                    content = @Content)
    })
    //
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> doLogin(HttpServletResponse response, @RequestBody @Valid UserLoginDTO userLoginDTO){
        return userService.doLogin(response, userLoginDTO);
    }

    //Documentation
    @Operation(summary = "Realize the user register")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User registered successfully",
                    content = { @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Map.class))}),
            @ApiResponse(responseCode = "401", description = "Email already in use",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid or missed credentials",
                    content = @Content)
    })
    //
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> doRegister(@RequestBody @Valid UserRegisterDTO userRegisterDTO){
        return userService.registerUser(userRegisterDTO);
    }

    @GetMapping("/google")
    public ResponseEntity<Map<String, Object>> googleAuth(@RequestParam("code") String code,
                                HttpServletResponse response){
        return userServiceGoogleOAuth.googleAuth(code, response);
    }


}


