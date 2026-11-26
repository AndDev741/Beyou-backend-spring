package beyou.beyouapp.backend.integration.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.enums.AiConsent;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

/**
 * A "no" to the AI provider must survive a save of the whole row that read the account before
 * the "no" was given: a profile edit, a check-in saving its principal. Two transactions, the
 * second one holding the stale entity, the way two requests race in production.
 */
class AiConsentWriteIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private TransactionTemplate transactionTemplate;

    private User newUser() {
        User account = new User();
        account.setName("consent");
        account.setEmail("consent-" + UUID.randomUUID() + "@test.com");
        account.setPassword("password123");
        account.setTimezone("UTC");
        account.setCompletedDays(new HashSet<>());
        account.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        account.setConstanceConfiguration(ConstanceConfiguration.ANY);
        account.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(account);
    }

    @Test
    void aStaleSaveCannotUndoANo() {
        UUID id = newUser().getId();
        transactionTemplate.executeWithoutResult(tx -> userService.setAiConsent(id, true));

        // Request A reads the account while the answer is still yes.
        User stale = transactionTemplate.execute(tx -> userRepository.findById(id).orElseThrow());
        assertThat(stale.getAiConsent()).isEqualTo(AiConsent.GRANTED);

        // Request B: the person says no.
        transactionTemplate.executeWithoutResult(tx -> userService.setAiConsent(id, false));

        // Request A saves the row it read, with a change of its own.
        stale.setName("renamed");
        transactionTemplate.executeWithoutResult(tx -> userRepository.saveAndFlush(stale));

        User now = userRepository.findById(id).orElseThrow();
        assertThat(now.getName()).isEqualTo("renamed");
        assertThat(now.getAiConsent()).isEqualTo(AiConsent.DECLINED);
        assertThat(now.getAiConsentAt()).isNotNull();
        assertThat(userRepository.findAiConsent(id)).isEqualTo(AiConsent.DECLINED);
    }

    @Test
    void aNewAccountStartsUnaskedInTheDatabase() {
        UUID id = newUser().getId();
        assertThat(userRepository.findAiConsent(id)).isEqualTo(AiConsent.UNASKED);
    }
}
