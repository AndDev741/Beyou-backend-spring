package beyou.beyouapp.backend.domain.notebook.card;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One answer to one card. Kept for two reasons: the review streak counts the days these fall on,
 * and the XP for a session is paid per row, once ({@code xpPaid}).
 */
@Entity
@Table(name = "notebook_card_reviews")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookCardReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CardRating rating;

    @Column(name = "review_date", nullable = false)
    private LocalDate reviewDate;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "xp_paid", nullable = false)
    private boolean xpPaid;
}
