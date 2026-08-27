package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

import beyou.beyouapp.backend.domain.routine.RoutineType;

/**
 * The create/update body for a routine, in both shapes.
 *
 * <p>Which of the two collections is read depends on {@link #type()}: a DAILY routine sends
 * {@code routineSections} and a LIST routine sends {@code items}. Sending the wrong one, or
 * both, is rejected by {@code DiaryRoutineService.validateRequestDTO} rather than quietly
 * half-applied.
 *
 * <p>No convenience constructor overload here, deliberately, even though adding two
 * components meant patching every construction site in the codebase. This is a
 * {@code @RequestBody} record: Jackson picks a constructor by reflection, and with two
 * available it can choose the wrong one and bind a request to a shape nobody sent. The
 * server-built {@code DiaryRoutineResponseDTO} is never deserialized and does carry one.
 */
public record DiaryRoutineRequestDTO(
        String name,
        String iconId,
        RoutineType type,
        List<RoutineSectionRequestDTO> routineSections,
        List<RoutineItemRequestDTO> items) {

    /**
     * The routine's shape, defaulting to DAILY when the caller said nothing.
     *
     * <p>An overridden accessor rather than a check at each call site: every client written
     * before the List type existed omits this field, as does every AI tool call that does not
     * mention it, and all of them must keep meaning exactly what they meant before. Doing it
     * here means no reader of this record can forget.
     */
    @Override
    public RoutineType type() {
        return type == null ? RoutineType.DAILY : type;
    }

    /**
     * {@code @JsonIgnore} because Jackson treats an {@code isX()} on a record as a derived
     * property and puts it on the wire: without this the request schema grows a phantom
     * {@code list} boolean that no client should send and the server would ignore, and the
     * generated TypeScript grows a field to match. Same reason as the two on
     * {@link RoutineItemRequestDTO}.
     */
    @JsonIgnore
    public boolean isList() {
        return type() == RoutineType.LIST;
    }
}
