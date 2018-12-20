FROM oracle/graalvm-ce:1.0.0-rc10

# To run, build jar using ./docker/build.sh

EXPOSE 8080
EXPOSE 5050

ENV GIT_MODEL_HASH $GIT_MODEL_HASH

RUN mkdir /sam
COPY ./sam*.jar /sam

# Build native executable https://www.graalvm.org/docs/reference-manual/aot-compilation/
# Add Sam as a service (it will start when the container starts)
CMD java $JAVA_OPTS -jar $(find /sam -name 'sam*.jar')
#RUN native-image $JAVA_OPTS -H:+ReportUnsupportedElementsAtRuntime \
#    --allow-incomplete-classpath \
#	--delay-class-initialization-to-runtime=org.ehcache.core.internal.service.ServiceLocator,com.getsentry.raven.event.EventBuilder,com.getsentry.raven.config.JndiLookup,com.getsentry.raven.RavenFactory,com.getsentry.raven.environment.RavenEnvironment,io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslServerContext,io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslContext,com.codahale.metrics.health.HealthCheckRegistry \
#    --rerun-class-initialization-at-runtime=org.httpkit.client.SslContextFactory \
#    -jar $(echo '/sam/sam*.jar') \
#    org.broadinstitute.dsde.workbench.sam.Boot
#
#CMD $(echo '/sam/sam*SNAPSHOT')