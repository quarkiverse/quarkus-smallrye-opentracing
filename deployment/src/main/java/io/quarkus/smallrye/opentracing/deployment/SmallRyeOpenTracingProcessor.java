package io.quarkus.smallrye.opentracing.deployment;

import java.lang.reflect.Method;

import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.servlet.DispatcherType;

import io.opentracing.Tracer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import io.quarkus.resteasy.common.spi.ResteasyJaxrsProviderBuildItem;
import io.quarkus.resteasy.reactive.spi.CustomContainerResponseFilterBuildItem;
import io.quarkus.resteasy.reactive.spi.DynamicFeatureBuildItem;
import io.quarkus.resteasy.reactive.spi.WriterInterceptorBuildItem;
import io.quarkus.smallrye.opentracing.runtime.QuarkusSmallRyeTracingDynamicFeature;
import io.quarkus.smallrye.opentracing.runtime.QuarkusSmallRyeTracingStandaloneContainerResponseFilter;
import io.quarkus.smallrye.opentracing.runtime.QuarkusSmallRyeTracingStandaloneVertxDynamicFeature;
import io.quarkus.smallrye.opentracing.runtime.TracerProducer;
import io.quarkus.undertow.deployment.FilterBuildItem;
import io.smallrye.opentracing.contrib.interceptor.OpenTracingInterceptor;
import io.smallrye.opentracing.contrib.jaxrs2.server.SpanFinishingFilter;

public class SmallRyeOpenTracingProcessor {

    private static final String FEATURE = "smallrye-opentracing";

    @BuildStep
    AdditionalBeanBuildItem registerBeans(BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        // Some components obtain the tracer via CDI.current().select(Tracer.class)
        // E.g. io.quarkus.smallrye.opentracing.runtime.QuarkusSmallRyeTracingDynamicFeature and io.smallrye.graphql.cdi.tracing.TracingService
        unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(Tracer.class));
        return new AdditionalBeanBuildItem(OpenTracingInterceptor.class, TracerProducer.class);
    }

    @BuildStep
    ReflectiveMethodBuildItem registerMethod() throws Exception {
        Method isAsync = ObserverMethod.class.getMethod("isAsync");
        return new ReflectiveMethodBuildItem(isAsync);
    }

    @BuildStep
    void setupFilter(
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ResteasyJaxrsProviderBuildItem> providers,
            BuildProducer<FilterBuildItem> filterProducer,
            BuildProducer<FeatureBuildItem> feature,
            BuildProducer<CustomContainerResponseFilterBuildItem> customResponseFilters,
            BuildProducer<DynamicFeatureBuildItem> dynamicFeatures,
            BuildProducer<WriterInterceptorBuildItem> writerInterceptors,
            Capabilities capabilities) {

        feature.produce(new FeatureBuildItem(FEATURE));

        additionalBeans.produce(new AdditionalBeanBuildItem(QuarkusSmallRyeTracingDynamicFeature.class));
        providers.produce(new ResteasyJaxrsProviderBuildItem(QuarkusSmallRyeTracingDynamicFeature.class.getName()));

        if (capabilities.isPresent(Capability.SERVLET)) {
            FilterBuildItem filterInfo = FilterBuildItem.builder("tracingFilter", SpanFinishingFilter.class.getName())
                    .setAsyncSupported(true)
                    .addFilterUrlMapping("*", DispatcherType.FORWARD)
                    .addFilterUrlMapping("*", DispatcherType.INCLUDE)
                    .addFilterUrlMapping("*", DispatcherType.REQUEST)
                    .addFilterUrlMapping("*", DispatcherType.ASYNC)
                    .addFilterUrlMapping("*", DispatcherType.ERROR)
                    .build();
            filterProducer.produce(filterInfo);
        } else if (capabilities.isPresent(Capability.RESTEASY)) {
            providers.produce(
                    new ResteasyJaxrsProviderBuildItem(QuarkusSmallRyeTracingStandaloneVertxDynamicFeature.class.getName()));
        } else if (capabilities.isPresent(Capability.RESTEASY_REACTIVE)) {
            customResponseFilters.produce(new CustomContainerResponseFilterBuildItem(
                    QuarkusSmallRyeTracingStandaloneContainerResponseFilter.class.getName()));
            dynamicFeatures.produce(new DynamicFeatureBuildItem(QuarkusSmallRyeTracingDynamicFeature.class.getName()));
            writerInterceptors.produce(
                    new WriterInterceptorBuildItem.Builder(
                            QuarkusSmallRyeTracingStandaloneContainerResponseFilter.class.getName()).build());
        }
    }

    @BuildStep
    void handleKafkaIntegration(BuildProducer<ReflectiveClassBuildItem> reflectiveClass, Capabilities capabilities) {
        //opentracing contrib kafka interceptors: https://github.com/opentracing-contrib/java-kafka-client
        if (!capabilities.isPresent(Capability.KAFKA)
                || !QuarkusClassLoader.isClassPresentAtRuntime("io.opentracing.contrib.kafka.TracingProducerInterceptor")) {
            return;
        }

        reflectiveClass.produce(ReflectiveClassBuildItem.builder("io.opentracing.contrib.kafka.TracingProducerInterceptor",
                "io.opentracing.contrib.kafka.TracingConsumerInterceptor").methods()
                .build());
    }

    @BuildStep
    void handleMongoDBIntegration(BuildProducer<AdditionalIndexedClassesBuildItem> additionalIndexedClasses,
            Capabilities capabilities) {
        if (!capabilities.isPresent(Capability.MONGODB_CLIENT)) {
            return;
        }

        additionalIndexedClasses.produce(new AdditionalIndexedClassesBuildItem(
                "io.quarkus.smallrye.opentracing.runtime.integration.MongoTracingCommandListener"));
    }

    @BuildStep
    void handleRestClientIntegration(
            Capabilities capabilities,
            BuildProducer<NativeImageResourceBuildItem> resource,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        if (capabilities.isPresent(Capability.REST_CLIENT) || capabilities.isPresent(Capability.REST_CLIENT_REACTIVE)) {
            resource.produce(new NativeImageResourceBuildItem(
                    "META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientListener"));
            reflectiveClass
                    .produce(ReflectiveClassBuildItem.builder("io.smallrye.opentracing.SmallRyeRestClientListener")
                            .methods().fields().build());
        }
    }
}
