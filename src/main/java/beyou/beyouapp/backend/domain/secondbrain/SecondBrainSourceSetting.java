package beyou.beyouapp.backend.domain.secondbrain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One switch the person has touched. A source with no row follows
 * {@link SecondBrainSource#enabledByDefault()}; {@link SecondBrainStore#effectiveSources} is the
 * one place that rule is read.
 *
 * <p>Read through JPA, written only by the upsert in {@link SecondBrainSourceSettingRepository}.
 */
@Entity
@Table(name = "second_brain_sources")
@IdClass(SecondBrainSourceSetting.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class SecondBrainSourceSetting {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private SecondBrainSource source;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The composite key: (user, source). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private SecondBrainSource source;
    }
}
