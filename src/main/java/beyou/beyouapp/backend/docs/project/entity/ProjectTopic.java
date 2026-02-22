package beyou.beyouapp.backend.docs.project.entity;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@Table(name = "docs_project_topic")
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ProjectTopic {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String key;

    @Column(nullable = false)
    private Integer orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectTopicStatus status = ProjectTopicStatus.ACTIVE;

    @OneToMany(mappedBy = "topic", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    private List<ProjectTopicContent> contents = new ArrayList<>();

    @Column(nullable = false)
    private Date createdAt;

    @Column(nullable = false)
    private Date updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDate now = LocalDate.now();
        setCreatedAt(Date.valueOf(now));
        setUpdatedAt(Date.valueOf(now));
    }

    @PreUpdate
    public void preUpdate() {
        setUpdatedAt(Date.valueOf(LocalDate.now()));
    }

    public Optional<ProjectTopicContent> findContentByLocale(String locale) {
        if (contents == null || locale == null) {
            return Optional.empty();
        }

        return contents.stream()
                .filter(content -> locale.equalsIgnoreCase(content.getLocale()))
                .findFirst();
    }
}