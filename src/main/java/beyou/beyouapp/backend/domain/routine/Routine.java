package beyou.beyouapp.backend.domain.routine;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

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
public abstract class Routine implements Persistable<UUID> {

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

    // Persistable.isNew() backing flag: defaults true for freshly-constructed
    // (never-persisted) instances so save() calls entityManager.persist() —
    // avoiding the merge()-triggered SELECT-then-INSERT "merge tax" on every
    // create. @PostLoad/@PostPersist flip it false once Hibernate has seen the
    // row, so update paths (which always load the managed entity first) keep
    // going through merge()/dirty-checking. Excluded from Lombok's class-level
    // @Getter/@Setter so the explicit isNew() override below doesn't collide.
    // Declared on Routine (not DiaryRoutine) because this is where @Id lives —
    // DiaryRoutine is the only Routine subtype today and inherits it.
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

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

    @PostLoad
    @PostPersist
    void markNotNew(){
        this.isNew = false;
    }

    @Override
    public boolean isNew(){
        return this.isNew;
    }

}