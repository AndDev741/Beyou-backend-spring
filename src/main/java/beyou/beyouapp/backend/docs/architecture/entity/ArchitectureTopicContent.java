package beyou.beyouapp.backend.docs.architecture.entity;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@Table(
    name = "docs_architecture_topic_content",
    uniqueConstraints = @UniqueConstraint(columnNames = { "topic_id", "locale" })
)
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ArchitectureTopicContent {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "topic_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    private ArchitectureTopic topic;

    @Column(nullable = false, length = 8)
    private String locale;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String diagramMermaid;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String docMarkdown;

    @Column(nullable = false)
    private Date updatedAt;

    @PrePersist
    public void prePersist() {
        setUpdatedAt(Date.valueOf(LocalDate.now()));
    }

    @PreUpdate
    public void preUpdate() {
        setUpdatedAt(Date.valueOf(LocalDate.now()));
    }
}
