package beyou.beyouapp.backend.unit.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import beyou.beyouapp.backend.docs.DocsLocale;
import beyou.beyouapp.backend.docs.api.ApiControllerService;
import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicService;
import beyou.beyouapp.backend.docs.blog.BlogTopicService;
import beyou.beyouapp.backend.docs.project.ProjectTopicService;

public class DocsLocaleTest {

    @Test
    public void shouldDefaultToEnglishWhenLocaleIsAbsent() {
        assertEquals("en", DocsLocale.normalize(null));
        assertEquals("en", DocsLocale.normalize(""));
        assertEquals("en", DocsLocale.normalize("   "));
    }

    @Test
    public void shouldCollapseCasingAndPaddingSoTheCacheDoesNotFragment() {
        assertEquals("pt", DocsLocale.normalize("PT"));
        assertEquals("pt", DocsLocale.normalize("  pt  "));
        assertEquals("en", DocsLocale.normalize("En"));
    }

    /**
     * The regression guard for the 400 on every list endpoint.
     *
     * <p>The service unit tests build the service with a plain constructor, so no
     * caching proxy exists and the key expression is never evaluated — which is
     * exactly why a null key shipped unnoticed. Here the annotation's SpEL is
     * evaluated directly with `locale` unset, the way a bare
     * `GET /docs/architecture/topics` reaches it.
     */
    @ParameterizedTest(name = "{0}#{1}")
    @MethodSource("cacheableDocsMethods")
    public void cacheKeyShouldResolveWhenLocaleIsOmitted(String className, String methodName, Method method) {
        String keyExpression = method.getAnnotation(Cacheable.class).key();

        StandardEvaluationContext context = new StandardEvaluationContext();
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            context.setVariable(parameter.getName(), null);
        }

        Object key = new SpelExpressionParser().parseExpression(keyExpression).getValue(context);

        assertNotNull(key, "Spring rejects a null cache key: " + className + "#" + methodName);
    }

    private static Stream<Arguments> cacheableDocsMethods() {
        List<Class<?>> services = List.of(
            ArchitectureTopicService.class,
            BlogTopicService.class,
            ProjectTopicService.class,
            ApiControllerService.class
        );

        return services.stream()
            .flatMap(service -> Stream.of(service.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Cacheable.class))
                .map(method -> Arguments.of(service.getSimpleName(), method.getName(), method)));
    }
}
