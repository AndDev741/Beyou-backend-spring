package beyou.beyouapp.backend.unit.user;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;

/**
 * The login response carries the streak scalars the frontend and the E2E suite assert on.
 * R15: they must be computed against the owner's local day, not the server's.
 */
class UserMapperUnitTest {

    private UserMapper userMapper;
    private User user;

    @BeforeEach
    void setup() {
        userMapper = new UserMapper();
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
        assertEquals(user.getCurrentConstance(ownerToday), response.constance());
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

        assertEquals("/api/v1/user/photo/" + user.getId() + "?v=1234", response.photo());
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
