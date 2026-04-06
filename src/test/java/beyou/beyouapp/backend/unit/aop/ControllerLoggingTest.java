package beyou.beyouapp.backend.unit.aop;

import beyou.beyouapp.backend.AOP.ControllerLogging;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControllerLoggingTest {

    @Test
    void logShouldNotContainMethodArguments() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(ControllerLogging.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn("ResponseEntity doLogin(HttpServletResponse, UserLoginDTO)");
        when(joinPoint.proceed()).thenReturn("ok");
        lenient().when(joinPoint.getArgs()).thenReturn(new Object[]{"secret-password-123"});

        ControllerLogging controllerLogging = new ControllerLogging();
        controllerLogging.logControllerAccess(joinPoint);

        String logMessage = listAppender.list.get(0).getFormattedMessage();

        assertTrue(logMessage.contains("doLogin"), "Log should contain method name");
        assertTrue(logMessage.contains("completed in"), "Log should contain duration");
        assertFalse(logMessage.contains("secret-password-123"), "Log must NOT contain argument values");
        assertFalse(logMessage.contains("args"), "Log must NOT reference args");

        logger.detachAppender(listAppender);
    }
}
