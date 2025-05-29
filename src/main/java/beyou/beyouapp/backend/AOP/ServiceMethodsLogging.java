package beyou.beyouapp.backend.AOP;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class ServiceMethodsLogging {

    @Pointcut("within(@org.springframework.stereotype.Service *)")
    public void allUserServiceMethods() {
    }

    @Before("allServiceMethods()")
    public void logBefore(JoinPoint joinpoint) {
        log.info("[START] Starting method: {} with args {}", joinpoint.getSignature().getName(), joinpoint.getArgs());
    }

    @AfterReturning(pointcut = "allUserServiceMethods()", returning = "result")
    public void logAfter(JoinPoint joinPoint, Object result) {
        log.info("[END] Method finish: {} ", joinPoint.getSignature().getName());
        log.info("[END] Return: {} ", result);
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
            log.error("[ERROR] Exception in method {}: {}", joinPoint.getSignature(), e.getMessage(), e);
            throw e; // Throw again to continue with the normal flux
        }
    }
}
