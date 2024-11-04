package beyou.beyouapp.backend.domain.habit;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table
@AllArgsConstructor
@NoArgsConstructor
public class Habit {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    @Size(min = 2, max = 256, message = "Name need a minimum of 2 characters")
    private String name;

    @Column
    @Size(max = 256, message = "The max of characters in description are 256")
    private String description;

    @Column(nullable = false)
    private String iconId;

    @Column
    @OneToMany
    @JoinColumn(name = "categoryId", nullable = true)
    private List<Category> categories;

    @Column
    private List<String> routines;

    @Column(nullable = false)
    private int importance;

    @Column(nullable = false)
    private int dificulty;

    @Column
    @Size(max = 256, message="The max of characters in motivation Phrase are 256")
    private String motivationalPhrase;

    @Column(nullable = false)
    private double xp;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false)
    private double nextLevelXp;

    @Column(nullable = false)
    private double actualBaseXp;

    @Column(nullable = false)
    private Date createdAt;

    @Column(nullable = false)
    private Date updatedAt;

    @Column
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
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

    public Habit(CreateHabitDTO createHabitDTO, double nextLevelXp, double actualBaseXp, User user){
        setName(createHabitDTO.name());
        setDescription(createHabitDTO.description());
        setIconId(createHabitDTO.iconId());
        setMotivationalPhrase(createHabitDTO.motivationalPhrase());
        setCategories(createHabitDTO.categories());
        setXp(createHabitDTO.xp());
        setLevel(createHabitDTO.level());
        setImportance(createHabitDTO.importance());
        setDificulty(createHabitDTO.dificulty());
        setNextLevelXp(nextLevelXp);
        setActualBaseXp(actualBaseXp);
        setUser(user);
    }
    
}