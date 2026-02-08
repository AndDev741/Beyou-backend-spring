package beyou.beyouapp.backend.controllers;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/user")
public class UserController {
    private UserService userService;
    private AuthenticatedUser authenticatedUser;

    public UserController(UserService userService, AuthenticatedUser authenticatedUser){
        this.userService = userService;
        this.authenticatedUser = authenticatedUser;
    }

    @PutMapping()
    public UserResponseDTO editUser(@Valid @RequestBody UserEditDTO userEdit){
        User user = authenticatedUser.getAuthenticatedUser();
        return userService.editUser(userEdit, user.getId());
    }
}
