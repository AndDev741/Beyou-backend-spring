package beyou.beyouapp.backend.utils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.common.DTO.RefreshItemCheckedDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshObjectDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;

public class RefreshUiDtoBuilder {
    public static RefreshUiDTO mockedRefreshUiDTO() {
        RefreshUserDTO refreshUser = new RefreshUserDTO(
            3,
            false,
            7,
            120.5,
            2,
            20.0,
            130.0
        );

        List<RefreshObjectDTO> refreshCategories = List.of(
            new RefreshObjectDTO(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                10.0,
                1,
                0.0,
                100.0
            ),
            new RefreshObjectDTO(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                25.0,
                2,
                5.0,
                80.0
            )
        );

        RefreshObjectDTO refreshHabit = new RefreshObjectDTO(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            15.0,
            1,
            2.0,
            50.0
        );

        TaskGroupCheck check = new TaskGroupCheck();
        check.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        check.setCheckDate(LocalDate.of(2024, 1, 15));
        check.setCheckTime(LocalTime.of(8, 30));
        check.setChecked(true);
        check.setXpGenerated(5.0);

        RefreshItemCheckedDTO refreshItemChecked = new RefreshItemCheckedDTO(
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            check
        );

        return new RefreshUiDTO(refreshUser, refreshCategories, refreshHabit, refreshItemChecked);
    }
}
