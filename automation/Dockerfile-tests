FROM broadinstitute/scala-baseimage:jdk11-2.13.5-1.4.7

COPY src /app/src
COPY test.sh /app
COPY project /app/project
COPY build.sbt /app

WORKDIR /app

ENTRYPOINT ["bash", "test.sh"]
