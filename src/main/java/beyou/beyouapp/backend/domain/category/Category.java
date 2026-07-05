package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.domain.Persistable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Entity
@Table(name = "categories")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Category implements Persistable<UUID> {
     // No @GeneratedValue/@UuidGenerator: Hibernate 7.4's merge() throws
     // StaleObjectStateException when a manually-assigned id coexists with a
     // generator annotation on an entity that has never been persisted (the
     // offline-sync replay path). Field-initializing the id keeps every other
     // construction path working (AI materialize flow, seeds, tests) since the
     // initializer runs on `new`, while letting mappers overwrite it with a
     // client-supplied UUID when present. save() on a pre-set id goes through
     // merge() (select-then-insert), and that select is the idempotency check.
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

     @NotBlank(message = "Category can't be empty")
     @Size(min = 2, max = 256, message = "Category need a minimum of 2 characters")
     @Column(nullable = false)
     private String name;

     @Size(max = 256, message = "The max o characters in description are 256")
     private String description;

     @Column(nullable = false)
     private String iconId;

     @JsonIgnore
     @ManyToMany(mappedBy = "categories")
     @ToString.Exclude
     @BatchSize(size = 100)
     private List<Habit> habits;

     @JsonIgnore
     @ManyToMany(mappedBy = "categories")
     @ToString.Exclude
     @BatchSize(size = 100)
     private List<Task> tasks;

     @JsonIgnore
     @ManyToMany(mappedBy = "categories")
     @ToString.Exclude
     @BatchSize(size = 100)
     private List<Goal> goals;

     @Embedded
     private XpProgress xpProgress = new XpProgress();

     private Date createdAt;
     private Date updatedAt;

     @JsonIgnore
     @ManyToOne
     @JoinColumn(name = "user_id", nullable = false)
     private User user;

     @PrePersist
     protected void onCreate(){
          LocalDate now = LocalDate.now();
          setCreatedAt(Date.valueOf(now));
          setUpdatedAt(Date.valueOf(now));
     }

     @PreUpdate
     protected void onUpdate(){
          LocalDate now = LocalDate.now();
          setUpdatedAt(Date.valueOf(now));
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

     public Category(CategoryRequestDTO categoryRequestDTO, User user){
          this.user = user;
          this.name = categoryRequestDTO.name();
          this.iconId = categoryRequestDTO.icon();
          this.description = categoryRequestDTO.description();
          this.xpProgress.setLevel(categoryRequestDTO.experience().getLevel());
          this.xpProgress.setXp(categoryRequestDTO.experience().getXp());
     }

     public void gainXp(double xp, Function<Integer, XpByLevel> provider){
          xpProgress.addXp(xp, provider);
     }

     public void loseXp(double xp, Function<Integer, XpByLevel> provider){
          xpProgress.removeXp(xp, provider);
     }
}
