package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

import beyou.beyouapp.backend.domain.routine.Routine;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class DiaryRoutine extends Routine {

    @OneToMany(mappedBy = "routine", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("orderIndex ASC")
    private List<RoutineSection> routineSections  = new ArrayList<>();
}