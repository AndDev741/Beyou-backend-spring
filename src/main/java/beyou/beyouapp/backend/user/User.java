package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import beyou.beyouapp.backend.user.enums.UserRole;

import java.time.LocalDate;
import java.util.Collection;
import java.sql.Date;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
public class User implements UserDetails {
    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false, unique = true)
    private UUID id;

    @NotBlank(message = "Name is Required")
    @Size(min = 2, message = "Name require a minimum of 2 characters")
    private String name;

    @NotBlank(message = "Email is Required")
    @Email(message = "Email is invalid")
    @Column(nullable = false, unique = true)
    private String email;

    @NotBlank(message = "Password is Required")
    @Size(min = 6, message = "Password require a minimum of 6 characters")
    private String password;

    private boolean isGoogleAccount;

    private String perfilPhrase;

    private String perfilPhraseAuthor;

    private String perfilPhoto;

    @NotNull
    @Min(value = 0, message = "The constance cannot be negative")
    private int constance;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Category> categories;

    private List<String> tasks;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Habit> habits;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Routine> routines;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Goal> goals;

    private Date createdAt;

    private Date updatedAt;

    @Embedded
    private XpProgress xpProgress = new XpProgress();

    @Enumerated(EnumType.STRING)
    private UserRole userRole;

    private List<String> widgetsIdInUse;

    private String themeInUse;

    @Enumerated(EnumType.STRING)
    private ConstanceConfiguration constanceConfiguration;

    @PrePersist
    protected void onUserCreate(){
        LocalDate now = LocalDate.now();
        setCreatedAt(Date.valueOf(now));
        setUpdatedAt(Date.valueOf(now));
        setUserRole(UserRole.USER);
        setConstance(0);
        getXpProgress().setActualLevelXp(0);;
        getXpProgress().setNextLevelXp(0D);
        getXpProgress().setLevel(0);
        getXpProgress().setXp(0D);
        setConstanceConfiguration(ConstanceConfiguration.ANY);
    }

    @PreUpdate
    protected void onUpdate(){
        setUpdatedAt(Date.valueOf(LocalDate.now()));
    }

    public User(UserRegisterDTO user){
        LocalDate now = LocalDate.now();
        setName(user.name());
        setEmail(user.email());
        setPassword(user.password());
        setGoogleAccount(user.isGoogleAccount());
        setUserRole(UserRole.USER);
        setConstance(0);
        setCreatedAt(Date.valueOf(now));
        setUpdatedAt(Date.valueOf(now));
    }

    public User(GoogleUserDTO googleUser) {
        LocalDate now = LocalDate.now();
        setName(googleUser.name());
        setEmail(googleUser.email());
        setPassword("GOOGLE_USER");
        setGoogleAccount(googleUser.isGoogleAccount());
        setPerfilPhoto(googleUser.perfilPhoto());
        setUserRole(UserRole.USER);
        setConstance(0);
        setCreatedAt(Date.valueOf(now));
        setUpdatedAt(Date.valueOf(now));
    }

    //UserDetails methods
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getUsername() {
        return getEmail();
    }
}
