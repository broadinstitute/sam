FROM oracle/graalvm-ce:1.0.0-rc10

# To run, build jar using ./docker/build.sh

EXPOSE 8080
EXPOSE 5050

ENV GIT_MODEL_HASH $GIT_MODEL_HASH

RUN mkdir /sam
COPY ./sam*.jar /sam

# Build native executable https://www.graalvm.org/docs/reference-manual/aot-compilation/
# Add Sam as a service (it will start when the container starts)
#CMD java $JAVA_OPTS -jar $(find /sam -name 'sam*.jar')
CMD echo $JAVA_OPTS
CMD java -version
CMD native-image $JAVA_OPTS -H:+ReportUnsupportedElementsAtRuntime -jar $(echo '/sam/sam*.jar')
CMD $(echo '/sam/sam*SNAPSHOT.jar')