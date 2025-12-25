package beyou.beyouapp.backend.domain.habit;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@Table
@AllArgsConstructor
@NoArgsConstructor
@ToString
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

    @ManyToMany
    @JoinTable(
    name = "habit_category",
    joinColumns = @JoinColumn(name = "habit_id"),
    inverseJoinColumns = @JoinColumn(name = "category_id"))
    private List<Category> categories;

    @Column
    private List<String> routines;

    @Column(nullable = false)
    private Integer importance;

    @Column(nullable = false)
    private Integer dificulty;

    @Column
    @Size(max = 256, message="The max of characters in motivation Phrase are 256")
    private String motivationalPhrase;

    @Embedded
    private XpProgress xpProgress = new XpProgress();

    @Column(nullable = true)
    private int constance;

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

    public Habit(CreateHabitDTO createHabitDTO, ArrayList<Category> categories, double nextLevelXp, double actualBaseXp, User user){
        setName(createHabitDTO.name());
        setDescription(createHabitDTO.description());
        setIconId(createHabitDTO.iconId());
        setMotivationalPhrase(createHabitDTO.motivationalPhrase());
        setCategories(categories);
        xpProgress.setXp(createHabitDTO.xp());
        xpProgress.setLevel(createHabitDTO.level());
        setImportance(createHabitDTO.importance());
        setDificulty(createHabitDTO.dificulty());
        setConstance(0);
        xpProgress.setNextLevelXp(nextLevelXp);
        xpProgress.setActualLevelXp(actualBaseXp);
        setUser(user);
    }
    
}