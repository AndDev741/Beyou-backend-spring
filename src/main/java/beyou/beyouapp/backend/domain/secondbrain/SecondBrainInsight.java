package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The generated overview of how a person's life is going. One row per account, keyed by the
 * account's id and replaced on every refresh.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(name = "second_brain_insights")
public class SecondBrainInsight {

    /** What the model is held to. The column in V45 is wider, so this can move without a migration. */
    public static final int TEXT_MAX = 900;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 1200)
    @ToString.Exclude
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SecondBrainVerdict verdict;
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
