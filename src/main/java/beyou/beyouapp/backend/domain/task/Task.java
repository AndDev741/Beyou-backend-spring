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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@Table
@NoArgsConstructor
@ToString
public class Task {
    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false, unique = true)
    UUID id;

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

    @Column(nullable = false)
    private Date createdAt;

    @Column(nullable = false)
    private Date updatedAt;

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
