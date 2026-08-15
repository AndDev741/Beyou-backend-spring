package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
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
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import beyou.beyouapp.backend.user.enums.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.sql.Date;
import java.util.List;
import java.util.Set;
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

    @Column(length = 512)
    private String perfilPhoto;

    Set<LocalDate> completedDays = new HashSet<>();

    Integer maxConstance = 0;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Category> categories;

    /**
     * Tasks were the one owned domain the user never mapped: this field used to be
     * a {@code List<String>} that nothing read and no table backed, so Hibernate
     * had no way to carry tasks off with their owner. Deleting an account that had
     * ever created a task failed with a transient-reference error, which is every
     * real account. Same shape as the four collections around it.
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Task> tasks;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Habit> habits;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Routine> routines;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Goal> goals;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RoutineSnapshot> routineSnapshots;

    private Date createdAt;

    private Date updatedAt;

    @Embedded
    private XpProgress xpProgress = new XpProgress();

    /**
     * R1 — the account-wide streak counters. Scalars only: the per-day history
     * is a side table, because this object is loaded in full by
     * {@code SecurityFilter} on every authenticated request (KTD2).
     */
    @Embedded
    private CheckProgress checkProgress = new CheckProgress();

    @Enumerated(EnumType.STRING)
    private UserRole userRole;

    private List<String> widgetsIdInUse;

    private String themeInUse;

    private String languageInUse;

    /** AI agent global memory — compact profile the model rewrites via updateGlobalContext. */
    @Column(length = 2000)
    private String userContext;

    @Enumerated(EnumType.STRING)
    private ConstanceConfiguration constanceConfiguration;

    @Column(nullable = false)
    private String timezone = "UTC";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private XpDecayStrategy xpDecayStrategy = XpDecayStrategy.GRADUAL;

    private boolean isTutorialCompleted;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean emailVerified = false;

    private String verificationToken;

    private LocalDateTime verificationTokenExpiry;

    @PrePersist
    protected void onUserCreate(){
        LocalDate now = LocalDate.now();
        setCreatedAt(Date.valueOf(now));
        setUpdatedAt(Date.valueOf(now));
        setUserRole(UserRole.USER);
        setMaxConstance(0);
        getXpProgress().setActualLevelXp(0);;
        getXpProgress().setNextLevelXp(0D);
        getXpProgress().setLevel(0);
        getXpProgress().setXp(0D);
        setConstanceConfiguration(ConstanceConfiguration.ANY);
        if (this.timezone == null) this.timezone = "UTC";
        if (this.xpDecayStrategy == null) this.xpDecayStrategy = XpDecayStrategy.GRADUAL;
        setTutorialCompleted(false);
        if (!this.emailVerified) this.emailVerified = false;
    }

    @PreUpdate
    protected void onUpdate(){
        setUpdatedAt(Date.valueOf(LocalDate.now()));
    }

    public User(UserRegisterDTO user){
        setName(user.name());
        setEmail(user.email());
        setPassword(user.password());
        setGoogleAccount(false);
    }

    public User(GoogleUserDTO googleUser) {
        setName(googleUser.name());
        setEmail(googleUser.email());
        setPassword("GOOGLE_USER");
        setGoogleAccount(googleUser.isGoogleAccount());
        setPerfilPhoto(googleUser.perfilPhoto());
        setEmailVerified(true);
    }

    // R14/KTD11 — the streak walk used to live here as getCurrentConstance(LocalDate),
    // counting calendar-consecutive completed days and returning zero outright whenever the
    // reference day sat more than one day past the last completed one. Both halves of that
    // are wrong for a user who is only scheduled some days of the week, and the fix needs
    // the account's frozen EntityCheckDay rows to tell a skipped day from an unscheduled
    // one — a repository this entity cannot reach. The walk now lives in
    // beyou.beyouapp.backend.domain.checkday.UserStreakService.

    //UserDetails methods
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Rows predating the role column (and any row a manual UPDATE left null)
        // must still authenticate — as ordinary users, never as admins.
        UserRole effectiveRole = userRole != null ? userRole : UserRole.USER;
        return List.of(new SimpleGrantedAuthority("ROLE_" + effectiveRole.name()));
    }

    @Override
    public String getUsername() {
        return getEmail();
    }
}
