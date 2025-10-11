package beyou.beyouapp.backend.controllers;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;

@RestController
@RequestMapping("/user")
public class UserController {
    private UserService userService;
    private AuthenticatedUser authenticatedUser;

    public UserController(UserService userService, AuthenticatedUser authenticatedUser){
        this.userService = userService;
        this.authenticatedUser = authenticatedUser;
    }

    @PutMapping("/edit")
    public ResponseEntity<Map<String, String>> editUser(@RequestBody UserEditDTO userEdit){
        User user = authenticatedUser.getAuthenticatedUser();
        try {
            userService.editUser(userEdit, user.getId());
            return ResponseEntity.ok(Map.of("success", "User edited successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to edit user")); 
        }
    }

    @PutMapping("/widgets")
    public ResponseEntity<Map<String, String>> editWidgets(@RequestBody UserEditDTO userEdit){
        User user = authenticatedUser.getAuthenticatedUser();
        try{
            userService.editWidgets(userEdit.widgetsId(), user.getId());
            return ResponseEntity.ok(Map.of("success", "Widgets edited successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to edit widgets"));
        }
    }
}
