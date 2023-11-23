package io.quarkus.smallrye.opentracing.deployment;

import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;

public class TracerHolder {

    public static MockTracer mockTracer = new MockTracer();

    static {
        GlobalTracer.registerIfAbsent(mockTracer);
    }
}
