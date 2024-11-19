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

@RestController
@RequestMapping(value = "/habit")
public class HabitController{
    @Autowired
    private HabitService habitService;

    @GetMapping("/{userId}")
    public ArrayList<Habit> getHabits(@PathVariable UUID userId){
        return habitService.getHabits(userId);
    }

    @PostMapping()
    public ResponseEntity<Map<String, String>> createHabit(@RequestBody CreateHabitDTO createHabitDTO){
        return habitService.createHabit(createHabitDTO);
    }

    @PutMapping()
    public ResponseEntity<Map<String, String>> editHabit(@RequestBody EditHabitDTO editHabitDTO){
        return habitService.editHabit(editHabitDTO);
    }

    @DeleteMapping(value = "/{habitId}")
    public ResponseEntity<Map<String, String>> deleteHabit(@PathVariable UUID habitId){
        return habitService.deleteHabit(habitId);
    }
}