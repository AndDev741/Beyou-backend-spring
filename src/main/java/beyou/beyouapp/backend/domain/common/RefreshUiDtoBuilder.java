package beyou.beyouapp.backend.domain.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.DTO.RefreshItemCheckedDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshObjectDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RefreshUiDtoBuilder {

    private final AuthenticatedUser authenticatedUser;
    private final UserService userService;

    public RefreshUiDTO buildRefreshUiDto(
            LocalDate date,
            Habit habitToRefresh,
            List<Category> categoriesToRefresh,
            RefreshItemCheckedDTO refreshItemCheckedDTO) {
        RefreshObjectDTO habitToRefreshDto = null;
        if (habitToRefresh != null) {
            habitToRefreshDto = new RefreshObjectDTO(
                    habitToRefresh.getId(),
                    habitToRefresh.getXpProgress().getXp(),
                    habitToRefresh.getXpProgress().getLevel(),
                    habitToRefresh.getXpProgress().getActualLevelXp(),
                    habitToRefresh.getXpProgress().getNextLevelXp());
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

        User userInContext = authenticatedUser.getAuthenticatedUser();
        User freshUser = userService.findUserById(userInContext.getId());
        RefreshUserDTO refreshUserDTO = new RefreshUserDTO(
                freshUser.getCurrentConstance(date),
                freshUser.getCompletedDays().contains(date),
                freshUser.getMaxConstance(),
                freshUser.getXpProgress().getXp(),
                freshUser.getXpProgress().getLevel(),
                freshUser.getXpProgress().getActualLevelXp(),
                freshUser.getXpProgress().getNextLevelXp());

        return new RefreshUiDTO(
                refreshUserDTO,
                categoriesToRefreshDto,
                habitToRefreshDto,
                refreshItemCheckedDTO);
    }
}
