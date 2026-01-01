package beyou.beyouapp.backend.domain.routine;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.user.User;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public abstract class Routine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String iconId;

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne
    @JoinColumn(name = "schedule_id", nullable = true)
    private Schedule schedule;

    @Embedded
    private XpProgress xpProgress = new XpProgress();

    @PrePersist
    protected void onUserCreate(){
        getXpProgress().setActualLevelXp(0);;
        getXpProgress().setNextLevelXp(0D);
        getXpProgress().setLevel(0);
        getXpProgress().setXp(0D);
    }

}