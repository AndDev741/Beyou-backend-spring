package beyou.beyouapp.backend.domain.common;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.category.Category;
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
                user.getCurrentConstance(date),
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
        LocalDate userToday = LocalDate.now(ZoneId.of(user.getTimezone()));
        RefreshUserDTO refreshUserDTO = new RefreshUserDTO(
                user.getCurrentConstance(userToday),
                user.getCompletedDays().contains(userToday),
                user.getMaxConstance(),
                user.getXpProgress().getXp(),
                user.getXpProgress().getLevel(),
                user.getXpProgress().getActualLevelXp(),
                user.getXpProgress().getNextLevelXp());

        return new RefreshUiDTO(refreshUserDTO, null, null, null);
    }
}
