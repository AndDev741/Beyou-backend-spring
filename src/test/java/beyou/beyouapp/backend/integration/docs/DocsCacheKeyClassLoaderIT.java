package beyou.beyouapp.backend.integration.docs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.docs.blog.BlogTopicService;

/**
 * Reproduces the 2026-09-09 production outage of the docs site.
 *
 * <p>Spring's cache layer keeps one shared {@code StandardEvaluationContext} and creates
 * its {@code TypeLocator} lazily, from the context class loader of whichever thread runs
 * the FIRST cache operation after boot. Every later {@code T(some.Class)} lookup in any
 * cache key, on any thread, then goes through that loader. In the packaged jar the app's
 * classes are visible only to Boot's {@code LaunchedClassLoader}, so a first thread that
 * carries the system loader poisons the docs keys until restart, with
 * {@code EL1005E: Type cannot be found}.
 *
 * <p>The test plays that first thread on purpose: it runs a cache operation with a context
 * class loader that cannot see the application, then calls the same service from a normal
 * thread. With a {@code T(...)} key that second call fails. With the bean reference
 * {@code @docsLocale.normalize(#locale)} the key never loads a class, so it passes.
 *
 * <p>Fresh context on purpose: the pin is per application context, and the test only
 * proves anything if it is the one doing the pinning.
 */
@DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
class DocsCacheKeyClassLoaderIT extends AbstractIntegrationTest {

    @Autowired
    private BlogTopicService blogTopicService;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void cacheKeySurvivesAFirstCacheOperationOnAThreadThatCannotSeeTheApp() throws Exception {
        // 1. Pin the shared type locator from a thread whose context class loader is the
        //    platform loader: JDK classes only, no Spring, no Beyou. Whatever the call does
        //    afterwards is irrelevant; the pin happens before the method body runs.
        AtomicReference<Throwable> firstCall = new AtomicReference<>();
        Thread poisoned = new Thread(() -> {
            try {
                blogTopicService.getTopics("en", null, null);
            } catch (Throwable t) {
                firstCall.set(t);
            }
        }, "first-cache-op-with-foreign-loader");
        poisoned.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        poisoned.start();
        poisoned.join();

        // 2. A normal request thread must still be able to evaluate the key.
        assertDoesNotThrow(() -> blogTopicService.getTopics("EN", null, null),
            () -> "docs cache key broke after a foreign-loader first call; first call threw: " + firstCall.get());

        // 3. And the key still normalises, so this is the real expression and not a
        //    silently-null one: ?locale=EN lands on the same entry as ?locale=en.
        Cache cache = cacheManager.getCache("blogTopics");
        assertNotNull(cache);
        assertNotNull(cache.get("en_null_null"), "expected the normalised key en_null_null in blogTopics");
        assertTrue(cache.get("EN_null_null") == null, "the raw locale must not get its own entry");
    }
}
