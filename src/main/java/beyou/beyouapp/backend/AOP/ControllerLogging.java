package beyou.beyouapp.backend.AOP;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class ControllerLogging {

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void allControllerMethods() {
    }

    @Around("allControllerMethods()")
    public Object logControllerAccess(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object result = joinPoint.proceed();

        long duration = System.currentTimeMillis() - start;
        log.info("[REQUEST] {} called with args {} - completed in {} ms",
                joinPoint.getSignature(), joinPoint.getArgs(), duration);

        return result;
    }

    @AfterThrowing(pointcut = "allControllerMethods()", throwing = "ex")
    public void logControllerExceptions(JoinPoint joinPoint, Throwable ex) {
        log.error("[EXCEPTION] Exception in {}: {}", joinPoint.getSignature(), ex.getMessage(), ex);
    }

}
