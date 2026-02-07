package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.routine.checks.CheckItemService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DiaryRoutineService {

    private static final int ITEM_TIME_TOLERANCE_MINUTES = 5;

    private final DiaryRoutineRepository diaryRoutineRepository;
    private final DiaryRoutineMapper mapper;
    private final CheckItemService checkItemService;

    @Transactional(readOnly = true)
    public DiaryRoutineResponseDTO getDiaryRoutineById(UUID id, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));
        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return mapper.toResponse(diaryRoutine);
    }

    @Transactional(readOnly = true)
    public DiaryRoutine getDiaryRoutineByScheduleId(UUID scheduleId, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return diaryRoutine;
    }

    @Transactional(readOnly = true)
    public DiaryRoutine getDiaryRoutineModelById(UUID id, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));
        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return diaryRoutine;
    }

    @Transactional(readOnly = true)
    public List<DiaryRoutineResponseDTO> getAllDiaryRoutines(UUID userId) {
        return diaryRoutineRepository.findAllByUserId(userId).stream()
                .map(mapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DiaryRoutine> getAllDiaryRoutinesModels(UUID userId) {
        return diaryRoutineRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toList());
    }

    @Transactional
    public DiaryRoutineResponseDTO createDiaryRoutine(DiaryRoutineRequestDTO dto, User user) {
        validateRequestDTO(dto);
        DiaryRoutine diaryRoutine = mapper.toEntity(dto);
        diaryRoutine.setUser(user);
        DiaryRoutine saved = diaryRoutineRepository.save(diaryRoutine);
        return mapper.toResponse(saved);
    }

    @Transactional
    public DiaryRoutineResponseDTO updateDiaryRoutine(UUID id, DiaryRoutineRequestDTO dto, UUID userId) {
        validateRequestDTO(dto);
        DiaryRoutine existing = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        if(!existing.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }

        existing.setName(dto.name());
        existing.setIconId(dto.iconId());
        existing.getRoutineSections().clear();
        List<RoutineSection> newSections = mapper.mapToRoutineSections(dto.routineSections(), existing);
        existing.getRoutineSections().addAll(newSections);

        DiaryRoutine updated = diaryRoutineRepository.save(existing);
        return mapper.toResponse(updated);
    }

    @Transactional
    public void saveRoutine(DiaryRoutine routine){
        diaryRoutineRepository.save(routine);
    }

    @Transactional
    public void deleteDiaryRoutine(UUID id, UUID userId) {
        Optional<DiaryRoutine> diaryRoutineToDelete = diaryRoutineRepository.findById(id);

        if (diaryRoutineToDelete.isEmpty() || !diaryRoutineToDelete.get().getUser().getId().equals(userId)) {
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }

        diaryRoutineRepository.deleteById(id);
    }

    @Transactional
    public DiaryRoutineResponseDTO getTodayRoutineScheduled(UUID userId){
        List<DiaryRoutine> diaryRoutines = diaryRoutineRepository.findAllByUserId(userId);

        DiaryRoutine todaysRoutine = null;
        String dayOfWeek = LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        log.info("Day: {} ", dayOfWeek);
        for (DiaryRoutine diaryRoutine : diaryRoutines) {
            if(diaryRoutine.getSchedule() != null && diaryRoutine.getSchedule().getDays().contains(WeekDay.valueOf(dayOfWeek))){
                log.info("Routine {} are scheduled for today", diaryRoutine.getName());
                todaysRoutine = diaryRoutine;
            }
        }

        if(todaysRoutine == null){
            log.warn("NO ROUTINES SCHEDULED FOR TODAY");
            return null;
        }else{
            return mapper.toResponse(todaysRoutine);
        }

    }

    private void validateRequestDTO(DiaryRoutineRequestDTO dto) {
        if (dto.name() == null || dto.name().trim().isEmpty()) {
            throw new IllegalArgumentException("DiaryRoutine name cannot be null or empty");
        }
        if (dto.routineSections() == null) {
            throw new IllegalArgumentException("Routine sections cannot be null");
        }
        for (var section : dto.routineSections()) {
            if (section.name() == null || section.name().trim().isEmpty()) {
                throw new IllegalArgumentException("Routine section name cannot be null or empty");
            }
            validateItemTimes(section);
        }
    }

    private void validateItemTimes(RoutineSectionRequestDTO section) {
        if (section.taskGroup() != null) {
            section.taskGroup().forEach(taskGroup -> validateItemTimeBounds(
                    section,
                    taskGroup.startTime(),
                    taskGroup.endTime(),
                    "task"
            ));
        }
        if (section.habitGroup() != null) {
            section.habitGroup().forEach(habitGroup -> validateItemTimeBounds(
                    section,
                    habitGroup.startTime(),
                    habitGroup.endTime(),
                    "habit"
            ));
        }
    }

    private void validateItemTimeBounds(
            RoutineSectionRequestDTO section,
            LocalTime startTime,
            LocalTime endTime,
            String itemType
    ) {
        if (endTime == null && startTime == null) {
            return;
        }

        LocalTime sectionStart = section.startTime();
        LocalTime sectionEnd = section.endTime();
        boolean sectionOvernight = isOvernight(sectionStart, sectionEnd);

        if (startTime != null && endTime != null && !sectionOvernight && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException(
                    "End time must be after start time for " + itemType + " in routine section: " + section.name());
        }

        if (sectionStart != null && sectionEnd != null) {
            if (startTime != null && !isWithinSectionWithTolerance(startTime, sectionStart, sectionEnd, sectionOvernight)) {
                throw new IllegalArgumentException(
                        "Start time must be within section bounds for " + itemType + " in routine section: " + section.name());
            }
            if (endTime != null && !isWithinSectionWithTolerance(endTime, sectionStart, sectionEnd, sectionOvernight)) {
                throw new IllegalArgumentException(
                        "End time must be within section bounds for " + itemType + " in routine section: " + section.name());
            }
        } else {
            if (sectionStart != null && startTime != null && startTime.isBefore(sectionStart.minusMinutes(ITEM_TIME_TOLERANCE_MINUTES))) {
                throw new IllegalArgumentException(
                        "Start time must be within section bounds for " + itemType + " in routine section: " + section.name());
            }
            if (sectionEnd != null && endTime != null && endTime.isAfter(sectionEnd.plusMinutes(ITEM_TIME_TOLERANCE_MINUTES))) {
                throw new IllegalArgumentException(
                        "End time must be within section bounds for " + itemType + " in routine section: " + section.name());
            }
        }
    }

    private boolean isOvernight(LocalTime startTime, LocalTime endTime) {
        return startTime != null && endTime != null && endTime.isBefore(startTime);
    }

    private boolean isWithinSectionWithTolerance(
            LocalTime time,
            LocalTime sectionStart,
            LocalTime sectionEnd,
            boolean sectionOvernight
    ) {
        int timeMinutes = toMinutes(time);
        int startMinutes = toMinutes(sectionStart);
        int endMinutes = toMinutes(sectionEnd);

        int minMinutes = startMinutes - ITEM_TIME_TOLERANCE_MINUTES;
        int maxMinutes = endMinutes + ITEM_TIME_TOLERANCE_MINUTES;

        if (!sectionOvernight) {
            minMinutes = Math.max(0, minMinutes);
            maxMinutes = Math.min(1439, maxMinutes);
            return timeMinutes >= minMinutes && timeMinutes <= maxMinutes;
        }

        int startWindow = Math.max(0, minMinutes);
        int endWindow = Math.min(1439, maxMinutes);

        return timeMinutes >= startWindow || timeMinutes <= endWindow;
    }

    private int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    @Transactional
    public RefreshUiDTO checkAndUncheckGroup(CheckGroupRequestDTO checkGroupRequestDTO, UUID userId){
        return checkItemService.checkOrUncheckItemGroup(checkGroupRequestDTO);
    }

    @Transactional
    public RefreshUiDTO skipOrUnskipGroup(SkipGroupRequestDTO skipGroupRequestDTO, UUID userId){
        return checkItemService.skipOrUnskipItemGroup(skipGroupRequestDTO);
    }

}
