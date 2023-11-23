package io.quarkus.smallrye.opentracing.deployment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.opentracing.mock.MockSpan;
import io.opentracing.util.GlobalTracerTestUtil;
import io.quarkus.smallrye.opentracing.deployment.TracerHolder;
import io.quarkus.smallrye.opentracing.runtime.integration.MongoTracingCommandListener;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Test the inclusion and config of the {@link MongoTracingCommandListener}.
 *
 * @see io.quarkus.smallrye.opentracing.deployment.TracingTest
 */
public class MongoTracingCommandListenerTest extends MongoTestBase {

    @Inject
    MongoClient client;

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(
                    () -> ShrinkWrap.create(JavaArchive.class).addClasses(MongoTestBase.class, TracerHolder.class))
            .withConfigurationResource("application-tracing-mongo.properties");

    @BeforeEach
    public void before() {
        TracerHolder.mockTracer.reset();
    }

    @AfterAll
    public static void afterAll() {
        GlobalTracerTestUtil.resetGlobalTracer();
    }

    @AfterEach
    void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void testClientInitialization() {
        assertThat(TracerHolder.mockTracer.finishedSpans()).isEmpty();

        assertThat(client.listDatabaseNames().first()).isNotEmpty();

        assertThat(TracerHolder.mockTracer.finishedSpans()).hasSize(1);
        MockSpan span = TracerHolder.mockTracer.finishedSpans().get(0);
        assertThat(span.operationName()).isEqualTo("listDatabases");
    }

}
