package beyou.beyouapp.backend.AOP;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class ServiceMethodsLogging {

    @Pointcut("within(@org.springframework.stereotype.Service *)")
    public void allUserServiceMethods() {
    }

    @Before("allUserServiceMethods()")
    public void logBefore(JoinPoint joinpoint) {
        log.info("[START] Starting method: {} with {} arg(s)", joinpoint.getSignature().getName(), joinpoint.getArgs().length);
    }

    @AfterReturning(pointcut = "allUserServiceMethods()", returning = "result")
    public void logAfter(JoinPoint joinPoint, Object result) {
        log.info("[END] Method finish: {} ", joinPoint.getSignature().getName());
        log.debug("[END] Return: {} ", result);
    }

    @Around("allUserServiceMethods()")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object result = joinPoint.proceed(); // Execute the intercepted method

        long duration = System.currentTimeMillis() - start;

        log.info("[PERFORMANCE] Method {} exectued in {} ms ", joinPoint.getSignature().getName(), duration);

        return result;
    }

    @Around("within(@org.springframework.stereotype.Service *)")
    public Object handleServiceExceptions(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            if (isExpectedClientError(e)) {
                log.warn("[CLIENT_ERROR] {} in {}: {}",
                        e.getClass().getSimpleName(),
                        joinPoint.getSignature().getName(),
                        e.getMessage());
            } else {
                log.error("[ERROR] Exception in method {}: {}",
                        joinPoint.getSignature(), e.getMessage(), e);
            }
            throw e; // Throw again to continue with the normal flux
        }
    }

    static boolean isExpectedClientError(Throwable e) {
        return e instanceof BusinessException
                || e instanceof JwtNotFoundException
                || e instanceof RefreshTokenNotFoundException
                || e instanceof RefreshTokenExpiredException
                || e instanceof RefreshTokenDontMatchRaw
                || e instanceof IllegalArgumentException;
    }
}
