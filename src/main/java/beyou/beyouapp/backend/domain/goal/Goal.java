package beyou.beyouapp.backend.domain.goal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.domain.Persistable;

@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(name = "goals")
public class Goal implements Persistable<UUID> {
    // No @GeneratedValue: Hibernate 7.4's merge() throws StaleObjectStateException
    // when a manually-assigned id coexists with a generator annotation on an
    // entity that has never been persisted (the offline-sync replay path).
    // Field-initializing the id keeps every other construction path working
    // (AI materialize flow, seeds, tests) since the initializer runs on `new`,
    // while letting the mapper overwrite it with a client-supplied UUID when
    // present. save() on a pre-set id goes through merge() (select-then-insert),
    // and that select is the idempotency check.
    @Id
    @Column(updatable = false, nullable = false)
    private UUID id = UUID.randomUUID();

    // Persistable.isNew() backing flag: defaults true for freshly-constructed
    // (never-persisted) instances so save() calls entityManager.persist() —
    // avoiding the merge()-triggered SELECT-then-INSERT "merge tax" on every
    // create. @PostLoad/@PostPersist flip it false once Hibernate has seen the
    // row, so update paths (which always load the managed entity first) keep
    // going through merge()/dirty-checking. Excluded from Lombok's class-level
    // @Getter/@Setter so the explicit isNew() override below doesn't collide.
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String iconId;

    @Column
    private String description;

    @Column(nullable = false)
    private Double targetValue;

    @Column(nullable = false)
    private String unit;

    @Column(nullable = false)
    private Double currentValue;

    @Column(nullable = false)
    private Boolean complete;


    @ManyToMany
    @JoinTable(
        name = "goal_category",
        joinColumns = @JoinColumn(name = "goal_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private List<Category> categories;

    @Column
    private String motivation;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private double xpReward;

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalTerm term;

    private LocalDate completeDate;

    @PostLoad
    @PostPersist
    void markNotNew(){
        this.isNew = false;
    }

    @Override
    public boolean isNew(){
        return this.isNew;
    }

    public Goal(CreateGoalRequestDTO dto, List<Category> categories, User user) {
        this.name = dto.name();
        this.iconId = dto.iconId();
        this.description = dto.description();
        this.targetValue = dto.targetValue();
        this.unit = dto.unit();
        this.currentValue = dto.currentValue();
        this.complete = false;
        this.categories = categories;
        this.motivation = dto.motivation();
        this.startDate = dto.startDate();
        this.endDate = dto.endDate();
        this.xpReward = 0;
        this.user = user;
        this.status = dto.status();
        this.term = dto.term();
    }
}