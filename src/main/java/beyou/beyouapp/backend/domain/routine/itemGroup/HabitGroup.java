package beyou.beyouapp.backend.domain.routine.itemGroup;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HabitGroup extends ItemGroup {

    @ManyToOne
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @OneToMany(mappedBy = "habitGroup", cascade = CascadeType.ALL, orphanRemoval = false)
    @BatchSize(size = 50)
    private List<HabitGroupCheck> habitGroupChecks = new ArrayList<>();;
}