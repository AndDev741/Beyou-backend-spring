package beyou.beyouapp.backend.domain.task;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "tasks")
@NoArgsConstructor
@ToString
public class Task implements Persistable<UUID> {
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
    UUID id = UUID.randomUUID();

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

    String name;

    String description;

    String iconId;

    Integer importance;

    Integer dificulty;

    private boolean oneTimeTask = false;

    private LocalDate markedToDelete;

    @ManyToMany
    @JoinTable(
    name = "task_category",
    joinColumns = @JoinColumn(name = "task_id"),
    inverseJoinColumns = @JoinColumn(name = "category_id"))
    List<Category> categories;

    // Field-initialized (not just @PrePersist) for the same reason as `id` above:
    // an offline-sync replay merges a freshly-built transient graph onto the
    // already-persisted row, and @PrePersist never fires on that UPDATE path —
    // a null default here would null out the NOT NULL created_at/updated_at
    // columns during merge.
    @Column(nullable = false)
    private Date createdAt = Date.valueOf(LocalDate.now());

    @Column(nullable = false)
    private Date updatedAt = Date.valueOf(LocalDate.now());

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

     @PrePersist
    public void prePersist(){
        LocalDate now = LocalDate.now();
        setCreatedAt(Date.valueOf(now));
        setUpdatedAt(Date.valueOf(now));
    }

    @PreUpdate
    public void preUpdate(){
        setUpdatedAt(Date.valueOf(LocalDate.now()));
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

    public Task(CreateTaskRequestDTO createTaskDTO, Optional<List<Category>> categories, User user){
        this.name = createTaskDTO.name();
        this.description = createTaskDTO.description();
        this.iconId = createTaskDTO.iconId();
        if(createTaskDTO.importance() != null) this.importance = createTaskDTO.importance();
        if(createTaskDTO.difficulty() != null) this.dificulty = createTaskDTO.difficulty();
        if(categories.isPresent()) this.categories = categories.get();
        this.user = user;
        this.oneTimeTask = createTaskDTO.oneTimeTask();
    }
}
