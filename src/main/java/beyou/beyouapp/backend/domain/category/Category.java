package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.cglib.core.Local;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "categories")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Category {
     @Id
     @GeneratedValue
     @Column(updatable = false, nullable = false, unique = true)
     private UUID id;

     @NotBlank(message = "Category can't be empty")
     @Size(min = 2, max = 256, message = "Category need a minimum of 2 characters")
     @Column(nullable = false)
     private String name;

     @Size(max = 256, message = "The max o characters in description are 256")
     private String description;

     @Column(nullable = false)
     private String iconId;

     private List<String> habits;
     private List<String> tasks;
     private List<String> goals;

     @Column(nullable = false)
     private double xp;

     @Column
     private double nextLevelXp;

     @Column(nullable = false)
     private int level;

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

     public Category(CategoryRequestDTO categoryRequestDTO, User user){
          this.user = user;
          this.name = categoryRequestDTO.name();
          this.iconId = categoryRequestDTO.icon();
          this.description = categoryRequestDTO.description();
          this.level = categoryRequestDTO.level();
          this.xp = categoryRequestDTO.xp();
     }
}
