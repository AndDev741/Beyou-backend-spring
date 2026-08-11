package beyou.beyouapp.backend.domain.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshItemCheckedDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshObjectDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RefreshUiDtoBuilder {

    /**
     * R14 — {@code currentConstance} on the check response is now a count of scheduled
     * days, not of calendar-consecutive ones, and only this service can tell the two
     * apart. The dependency is the signal: the meaning of that field changed, and every
     * caller of this builder had to be recompiled to keep it.
     *
     * <p>The number is computed here rather than read off a stored scalar on purpose. The
     * response has to report the streak the check just produced, with no scheduler run in
     * between, and {@code DayCloseService} overwrites {@code User.checkProgress} from the
     * account's stored rows — which reach only as far back as the last grace hour, never to
     * today — so a cached scalar there would always be a day stale.
     */
    private final UserStreakService userStreakService;

    public RefreshUiDTO buildRefreshUiDto(
            LocalDate date,
            Habit habitToRefresh,
            List<Category> categoriesToRefresh,
            RefreshItemCheckedDTO refreshItemCheckedDTO,
            User user) {
        RefreshObjectDTO habitToRefreshDto = null;
        if (habitToRefresh != null) {
            // R21 — the checked habit's recomputed streak, record and total ride back with
            // the XP, so the card the user just tapped repaints from this one response.
            // Null-guarded the same way HabitMapper guards xpProgress: a habit row written
            // before V13 can still materialise a null embeddable.
            CheckProgress checkProgress = habitToRefresh.getCheckProgress();
            habitToRefreshDto = new RefreshObjectDTO(
                    habitToRefresh.getId(),
                    habitToRefresh.getXpProgress().getXp(),
                    habitToRefresh.getXpProgress().getLevel(),
                    habitToRefresh.getXpProgress().getActualLevelXp(),
                    habitToRefresh.getXpProgress().getNextLevelXp(),
                    checkProgress != null ? checkProgress.getCurrentStreak() : 0,
                    checkProgress != null ? checkProgress.getBestStreak() : 0,
                    checkProgress != null ? checkProgress.getTotalCheckIns() : 0);
        }

        List<RefreshObjectDTO> categoriesToRefreshDto = new ArrayList<RefreshObjectDTO>();
        if (categoriesToRefresh != null) {
            categoriesToRefresh.forEach(c -> {
                categoriesToRefreshDto.add(
                        new RefreshObjectDTO(
                                c.getId(),
                                c.getXpProgress().getXp(),
                                c.getXpProgress().getLevel(),
                                c.getXpProgress().getActualLevelXp(),
                                c.getXpProgress().getNextLevelXp()));
            });
        }

        RefreshUserDTO refreshUserDTO = new RefreshUserDTO(
                userStreakService.streakOf(user, date).currentStreak(),
                user.getCompletedDays().contains(date),
                user.getMaxConstance(),
                user.getXpProgress().getXp(),
                user.getXpProgress().getLevel(),
                user.getXpProgress().getActualLevelXp(),
                user.getXpProgress().getNextLevelXp());

        return new RefreshUiDTO(
                refreshUserDTO,
                categoriesToRefreshDto,
                habitToRefreshDto,
                refreshItemCheckedDTO);
    }

    public RefreshUiDTO buildSnapshotRefreshUiDto(User user) {
        // R15 — through the resolver rather than ZoneId.of(user.getTimezone()) directly,
        // which throws on a null or unparseable zone instead of falling back to the
        // server's. The streak read below already goes through it; the two must agree on
        // what day it is or the response contradicts itself.
        LocalDate userToday = UserDateResolver.today(user);
        RefreshUserDTO refreshUserDTO = new RefreshUserDTO(
                userStreakService.streakOf(user, userToday).currentStreak(),
                user.getCompletedDays().contains(userToday),
                user.getMaxConstance(),
                user.getXpProgress().getXp(),
                user.getXpProgress().getLevel(),
                user.getXpProgress().getActualLevelXp(),
                user.getXpProgress().getNextLevelXp());

        return new RefreshUiDTO(refreshUserDTO, null, null, null);
    }
}
