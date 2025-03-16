package beyou.beyouapp.backend.controllers;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@RestController
@RequestMapping(value = "/habit")
public class HabitController{
    @Autowired
    private HabitService habitService;

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @GetMapping("")
    public ArrayList<Habit> getHabits(){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return habitService.getHabits(userAuth.getId());
    }

    @PostMapping()
    public ResponseEntity<Map<String, String>> createHabit(@RequestBody CreateHabitDTO createHabitDTO){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return habitService.createHabit(createHabitDTO, userAuth.getId());
    }

    @PutMapping()
    public ResponseEntity<Map<String, String>> editHabit(@RequestBody EditHabitDTO editHabitDTO){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return habitService.editHabit(editHabitDTO, userAuth.getId());
    }

    @DeleteMapping(value = "/{habitId}")
    public ResponseEntity<Map<String, String>> deleteHabit(@PathVariable UUID habitId){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return habitService.deleteHabit(habitId, userAuth.getId());
    }
}