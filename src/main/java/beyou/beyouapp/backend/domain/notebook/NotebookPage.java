package beyou.beyouapp.backend.domain.notebook;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.habit.Habit;
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
 * One page of the study notebook. A topic is a page too: the root, with no parent.
 *
 * <p>{@code parentId} and {@code topicId} are plain ids rather than associations. Every read
 * of the tree loads a topic's pages in one query and assembles them in memory
 * ({@link NotebookTree}), so a lazy parent proxy would only be a way to trigger one query per
 * page by accident.
 *
 * <p>{@code content} is the BlockNote document as JSON, and {@code contentText} is the plain
 * text {@link BlockText} extracted from it on the server. The AI reads the second one, and it
 * is never taken from the client, because a client could send any text it liked as "what the
 * page says".
 *
 * <p>{@code status} is written only by {@link NotebookProgressService}. See there for why it is
 * stored rather than computed on every read.
 */
@Entity
@Table(name = "notebook_pages")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookPage {

    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 512;
    public static final int MAX_ICON_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "topic_id")
    private UUID topicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotebookPageKind kind;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(length = MAX_ICON_LENGTH)
    private String icon;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(columnDefinition = "text")
    @ToString.Exclude
    private String content;

    @Column(name = "content_text", columnDefinition = "text")
    @ToString.Exclude
    private String contentText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotebookStatus status = NotebookStatus.TO_STUDY;

    @Column(name = "status_manual", nullable = false)
    private boolean statusManual;

    @Column(nullable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goal_id")
    @ToString.Exclude
    private Goal goal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id")
    @ToString.Exclude
    private Habit habit;

    @Column(name = "done_xp_at")
    private Instant doneXpAt;

    @Column(name = "last_opened_at")
    private Instant lastOpenedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A topic: the root of its own tree. */
    public static NotebookPage topic(User user, String title, Instant now) {
        NotebookPage page = new NotebookPage();
        page.user = user;
        page.kind = NotebookPageKind.TOPIC;
        page.title = title;
        page.createdAt = now;
        page.updatedAt = now;
        return page;
    }

    /** A page under {@code parent}, in the parent's topic. */
    public static NotebookPage childOf(NotebookPage parent, String title, Instant now) {
        NotebookPage page = new NotebookPage();
        page.user = parent.getUser();
        page.kind = NotebookPageKind.PAGE;
        page.parentId = parent.getId();
        page.topicId = parent.rootId();
        page.title = title;
        page.createdAt = now;
        page.updatedAt = now;
        return page;
    }

    /** The topic this page belongs to: itself when it is one. */
    public UUID rootId() {
        return kind == NotebookPageKind.TOPIC ? id : topicId;
    }

    public boolean isTopic() {
        return kind == NotebookPageKind.TOPIC;
    }
}
