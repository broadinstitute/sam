FROM mcr.microsoft.com/devcontainers/java:dev-17-buster

# To run, build jar using ./docker/build.sh

EXPOSE 8080
EXPOSE 5050

ENV GIT_MODEL_HASH $GIT_MODEL_HASH

RUN mkdir /sam
COPY ./sam*.jar /sam
# Add Sam as a service (it will start when the container starts)
# 1. “Exec” form of CMD necessary to avoid “shell” form’s `sh` stripping 
#    environment variables with periods in them, often used in DSP for Lightbend 
#    config.
# 2. Handling $JAVA_OPTS is necessary as long as firecloud-develop or the app’s 
#    chart tries to set it. Apps that use devops’s foundation subchart don’t need 
#    to handle this.
# 3. The jar’s location and naming scheme in the filesystem is required by preflight 
#    liquibase migrations in some app charts. Apps that expose liveness endpoints 
#    may not need preflight liquibase migrations.
# We use the “exec” form with `bash` to accomplish all of the above.
CMD ["/bin/bash", "-c", "java $JAVA_OPTS -jar $(find /sam -name 'sam*.jar')"]
