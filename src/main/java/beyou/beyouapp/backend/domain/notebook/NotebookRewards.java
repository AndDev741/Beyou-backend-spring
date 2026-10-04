package beyou.beyouapp.backend.domain.notebook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * Every XP payment the notebook makes, in one place.
 *
 * <p>Three things pay, and each is guarded against paying twice by the caller's own column:
 * a page reaching DONE for the first time ({@code done_xp_at}), a quiz passed for the first
 * time ({@code passed_at}), and a finished review session ({@code xp_paid} per review, with a
 * daily cap). The amounts live here so the three can be compared at a glance.
 *
 * <p>XP goes to the person and to the topic's category, the same pairing a habit uses minus
 * the habit. A topic with no category pays the person only.
 */
@Component
@RequiredArgsConstructor
public class NotebookRewards {

    /** A page reaching DONE for the first time. */
    public static final double PAGE_DONE_XP = 15;
    /** A quiz passed for the first time. */
    public static final double QUIZ_PASSED_XP = 20;
    /** One reviewed card. */
    public static final double CARD_REVIEW_XP = 1;
    /** Reviewed cards paid per day. Past this, reviewing still schedules cards, it just stops paying. */
    public static final int DAILY_REVIEW_XP_CAP = 30;

    private final XpCalculatorService xpCalculatorService;
    private final RefreshUiDtoBuilder refreshUiDtoBuilder;
    private final UserCacheEvictService userCacheEvictService;

    /**
     * Pays one amount and returns what the client needs to repaint.
     *
     * <p>{@code user} must be the managed entity from the current transaction (the page's owner
     * as loaded with it), never the detached principal from the security context: XP is written
     * through it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefreshUiDTO pay(User user, Category category, double xp) {
        Payroll payroll = new Payroll();
        payroll.add(category, xp);
        return pay(user, payroll);
    }

    /**
     * Pays several amounts, possibly to several categories (a status change can finish pages in
     * two topics when one is linked into the other), and builds ONE repaint for all of them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefreshUiDTO pay(User user, Payroll payroll) {
        payroll.byCategory.values().forEach(line ->
                xpCalculatorService.addXpToUserAndCategoriesAndPersist(
                        user, line.xp, line.category == null ? List.of() : List.of(line.category)));
        // Category and profile XP sit behind the user-scoped caches.
        userCacheEvictService.evictAllUserCaches(user.getId());
        return refreshUiDtoBuilder.buildRefreshUiDto(
                UserDateResolver.today(user), null, payroll.categories(), null, user);
    }

    /** XP owed, grouped by the category it goes to. Null category means the person only. */
    public static final class Payroll {
        private final Map<UUID, Line> byCategory = new LinkedHashMap<>();

        public void add(Category category, double xp) {
            if (xp <= 0) return;
            UUID key = category == null ? null : category.getId();
            byCategory.computeIfAbsent(key, k -> new Line(category)).xp += xp;
        }

        public boolean isEmpty() {
            return byCategory.isEmpty();
        }

        public double total() {
            return byCategory.values().stream().mapToDouble(l -> l.xp).sum();
        }

        Collection<Category> categoryList() {
            return byCategory.values().stream().map(l -> l.category).filter(c -> c != null).toList();
        }

        List<Category> categories() {
            return new ArrayList<>(categoryList());
        }
    }

    private static final class Line {
        private final Category category;
        private double xp;

        private Line(Category category) {
            this.category = category;
        }
    }
}
