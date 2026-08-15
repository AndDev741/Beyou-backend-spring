package beyou.beyouapp.backend.domain.routine;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.domain.common.CheckProgress;
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

    /**
     * The days this routine runs on, or null when it was built and never scheduled.
     *
     * <p>{@code CascadeType.REMOVE} because a schedule belongs to exactly one routine and
     * has no other way home: the FK lives on this side, the schedules table is nothing but
     * an id, and nothing references a user. Without the cascade, deleting a routine left
     * the schedule row and its {@code schedule_days} behind with no owner and no way to
     * ever find them again. Both delete paths leaked it — {@code deleteDiaryRoutine}, and
     * account deletion by way of {@code User.routines}. {@code V18} clears the rows that
     * were stranded before this line existed.
     *
     * <p>Not {@code orphanRemoval}: {@link
     * beyou.beyouapp.backend.domain.routine.schedule.ScheduleService#delete} unschedules by
     * setting this to null and then deleting the row itself, and orphan removal would make
     * that the same delete twice.
     */
    @OneToOne(cascade = CascadeType.REMOVE)
    @JoinColumn(name = "schedule_id", nullable = true)
    private Schedule schedule;

    @Embedded
    private XpProgress xpProgress = new XpProgress();

    /**
     * R1 — this routine's own streak counters. The columns are NOT NULL in
     * {@code V13}, which SINGLE_TABLE inheritance only tolerates because
     * DiaryRoutine is the sole subclass; a second subclass that does not get
     * checked would have to make them nullable again.
     */
    @Embedded
    private CheckProgress checkProgress = new CheckProgress();

    @PrePersist
    protected void onUserCreate(){
        getXpProgress().setActualLevelXp(0);;
        getXpProgress().setNextLevelXp(0D);
        getXpProgress().setLevel(0);
        getXpProgress().setXp(0D);
    }

}