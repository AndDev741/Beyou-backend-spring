package beyou.beyouapp.backend.unit.routine;

import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryRoutineServiceCheckOwnershipTest {

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @InjectMocks
    private DiaryRoutineService diaryRoutineService;

    @Test
    void checkAndUncheckGroup_shouldRejectWhenUserDoesNotOwnRoutine() {
        UUID routineId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID attackerUserId = UUID.randomUUID();

        User owner = new User();
        owner.setId(ownerUserId);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setUser(owner);

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));

        CheckGroupRequestDTO dto = new CheckGroupRequestDTO(routineId, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> diaryRoutineService.checkAndUncheckGroup(dto, attackerUserId));

        assertEquals(ErrorKey.ROUTINE_NOT_OWNED, ex.getErrorKey());
    }

    @Test
    void skipOrUnskipGroup_shouldRejectWhenUserDoesNotOwnRoutine() {
        UUID routineId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID attackerUserId = UUID.randomUUID();

        User owner = new User();
        owner.setId(ownerUserId);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setUser(owner);

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(routineId, null, null, null, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> diaryRoutineService.skipOrUnskipGroup(dto, attackerUserId));

        assertEquals(ErrorKey.ROUTINE_NOT_OWNED, ex.getErrorKey());
    }
}
