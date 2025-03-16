package beyou.beyouapp.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.user.User;

@Component
public class AuthenticatedUser {
    public User getAuthenticatedUser(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if(authentication == null || !(authentication.getPrincipal() instanceof User)){
            throw new RuntimeException("User not authenticated");
        }
        return (User) authentication.getPrincipal();
    }
}
