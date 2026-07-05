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
@Table(name = "routines")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public abstract class Routine {

    // No @GeneratedValue: Hibernate 7.4's merge() throws StaleObjectStateException
    // when a manually-assigned id coexists with a generator annotation on an
    // entity that has never been persisted (the offline-sync replay path).
    // Field-initializing the id keeps every other construction path working
    // (AI materialize flow, seeds, tests) since the initializer runs on `new`,
    // while letting the mapper overwrite it with a client-supplied UUID when
    // present. save() on a pre-set id goes through merge() (select-then-insert),
    // and that select is the idempotency check.
    @Id
    private UUID id = UUID.randomUUID();

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