package beyou.beyouapp.backend.unit.user;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.PhotoUrlSigner;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;

/**
 * The login response carries the streak scalars the frontend and the E2E suite assert on.
 * R15: they must be computed against the owner's local day, not the server's.
 */
class UserMapperUnitTest {

    private UserMapper userMapper;
    private EntityCheckDayRepository entityCheckDayRepository;
    private PhotoUrlSigner photoUrlSigner;
    private User user;

    @BeforeEach
    void setup() {
        // The real walk over a stubbed row store: these tests are about which DAY the
        // scalars are read against, so the scheduling half stays empty and every gap day
        // reads as neutral.
        entityCheckDayRepository = mock(EntityCheckDayRepository.class);
        photoUrlSigner = new PhotoUrlSigner("a-token-secret-for-tests", 720);
        userMapper = new UserMapper(new UserStreakService(entityCheckDayRepository), photoUrlSigner);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Owner");
        user.setEmail("owner@test.com");
        user.setXpProgress(new XpProgress(120D, 3, 20D, 200D));
        user.setCompletedDays(new HashSet<>());
        user.setMaxConstance(5);
    }

    @Test
    void shouldReportDayCompletedAgainstTheOwnersLocalDay() {
        ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
        user.setTimezone(ownerZone.getId());
        LocalDate ownerToday = LocalDate.now(ownerZone);
        user.setCompletedDays(new HashSet<>(Set.of(ownerToday)));

        UserResponseDTO response = userMapper.toResponseDTO(user);

        assertTrue(response.constanceIncreaseToday(),
                "The owner completed their local today, so the flag must be true");
        assertEquals(1, response.constance(),
                "One completed day, counted against the owner's local today");
        assertFalse(response.constanceDormant(),
                "R20 — nothing has been unscheduled for fourteen days; the run is live");
    }

    @Test
    void shouldReportADormantStreakWhenNothingHasBeenScheduledForFourteenDays() {
        // R20/KTD25 — the number survives; only the flag says the run has gone quiet.
        user.setTimezone(ZoneId.systemDefault().getId());
        LocalDate today = LocalDate.now();
        user.setCompletedDays(new HashSet<>(Set.of(today.minusDays(20))));
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.USER, user.getId()))
                .thenReturn(List.of(new EntityCheckDay(user, CheckDayOwnerType.USER, user.getId(),
                        today.minusDays(19), CheckDayOutcome.NOT_IN_ROUTINE)));

        UserResponseDTO response = userMapper.toResponseDTO(user);

        assertEquals(1, response.constance(), "The run is not zeroed, only flagged");
        assertTrue(response.constanceDormant());
    }

    @Test
    void shouldNotReportDayCompletedWhenOnlyTheServersDayIsMarked() {
        ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
        user.setTimezone(ownerZone.getId());
        user.setCompletedDays(new HashSet<>(Set.of(LocalDate.now())));

        UserResponseDTO response = userMapper.toResponseDTO(user);

        assertFalse(response.constanceIncreaseToday(),
                "The server's day is not the owner's day — the flag must not be set");
    }

    @Test
    void shouldFallBackToTheServerZoneWhenTheOwnerHasNoTimezone() {
        user.setTimezone(null);
        user.setCompletedDays(new HashSet<>(Set.of(LocalDate.now())));

        UserResponseDTO response = assertDoesNotThrow(() -> userMapper.toResponseDTO(user));

        assertTrue(response.constanceIncreaseToday());
    }

    @Test
    void shouldFallBackToTheServerZoneWhenTheOwnersTimezoneIsGarbage() {
        user.setTimezone("Not/AZone");
        user.setCompletedDays(new HashSet<>(Set.of(LocalDate.now())));

        UserResponseDTO response = assertDoesNotThrow(() -> userMapper.toResponseDTO(user));

        assertTrue(response.constanceIncreaseToday());
    }

    @Test
    void shouldVersionThePhotoUrlWhenAPhotoVersionIsGiven() {
        user.setTimezone(ZoneId.systemDefault().getId());

        UserResponseDTO response = userMapper.toResponseDTO(user, 1234L);

        assertTrue(response.photo().startsWith("/api/v1/user/photo/" + user.getId() + "?v=1234&"),
                "the version still leads the query so image caches bust on upload: " + response.photo());
    }

    /**
     * The photo endpoint has no header to authenticate with — an {@code <img src>}
     * cannot send one — so the URL carries its own proof, and this response is the
     * only place it is ever minted.
     */
    @Test
    void shouldSignThePhotoUrlSoOnlyTheOwnerCanLoadIt() {
        user.setTimezone(ZoneId.systemDefault().getId());

        UserResponseDTO response = userMapper.toResponseDTO(user, 1234L);

        Map<String, String> query = queryOf(response.photo());
        assertTrue(photoUrlSigner.isValid(user.getId(), query.get("exp"), query.get("sig")),
                "the minted URL should validate for its own owner");
        assertFalse(photoUrlSigner.isValid(UUID.randomUUID(), query.get("exp"), query.get("sig")),
                "a signature must not carry over to another user's id");
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    /**
     * UTC+14 and UTC-12 sit 26 hours apart, so their local dates never coincide — at any
     * instant at least one of them is on a different calendar day than the server.
     */
    private static ZoneId zoneWhoseTodayDiffersFromServer() {
        LocalDate serverToday = LocalDate.now();
        for (String zoneId : List.of("Etc/GMT-14", "Etc/GMT+12")) {
            ZoneId zone = ZoneId.of(zoneId);
            if (!LocalDate.now(zone).equals(serverToday)) {
                return zone;
            }
        }
        throw new IllegalStateException("No zone differed from the server's day — impossible by construction");
    }
}
