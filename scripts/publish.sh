#!/usr/bin/env bash

set -e

timestamp() {
  date +"%T"
}

function publish ()
{
    echo "getting swagger codegen jar..."
    wget http://central.maven.org/maven2/io/swagger/swagger-codegen-cli/2.2.3/swagger-codegen-cli-2.2.3.jar -O swagger-codegen-cli.jar
    java -jar swagger-codegen-cli.jar
    java -jar swagger-codegen-cli.jar help

    echo "generating Sam Java Client API..."
    java -jar swagger-codegen-cli.jar generate -l java --input-spec ./src/main/resources/swagger/api-docs.yaml --output generated

    echo "adding plugins file..."
    cd generated
    mkdir project
    echo "addSbtPlugin(\"com.eed3si9n\" % \"sbt-assembly\" % \"0.14.3\")" > project/plugins.sbt
    sed -i 's/publishArtifact in (Compile, packageDoc) := false/publishTo := Some(Resolver.file(\"file\", new File(\"publish\")))/g' build.sbt

    echo "compiling Java Client API..."
    sbt assembly
    sbt compile
    sbt publish
    find publish -name "*.md5" -type f -delete
    find publish -name "*.sha1" -type f -delete

    echo "pushing to Artifactory..."
    printenv
    echo "ls publish/io/swagger/swagger-java-client_2.11/1.0.0"
    ls publish/io/swagger/swagger-java-client_2.11/1.0.0
    chmod a+wx publish
#    Scala version    $TRAVIS_SCALA_VERSION
#    Version
#    Hash   TRAVIS_COMMIT=8c8efdf797894253c017f703d6ba562b05d23448

#    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-hash/swagger-java-client_2.11-1.0.0.jar" -T publish/io/swagger/swagger-java-client_2.11/1.0.0/swagger-java-client_2.11-1.0.0.jar


#    curl -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-snapshot-build.timestamp=" + timestamp -T Desktop/myNewFile.txt
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-hash/swagger-java-client_2.11-1.0.0.jar" -T publish/io/swagger/swagger-java-client_2.11/1.0.0/swagger-java-client_2.11-1.0.0.jar
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-hash/swagger-java-client_2.11-1.0.0.pom" -T publish/io/swagger/swagger-java-client_2.11/1.0.0/swagger-java-client_2.11-1.0.0.pom
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-hash/swagger-java-client_2.11-1.0.0-javadoc.jar" -T publish/io/swagger/swagger-java-client_2.11/1.0.0/swagger-java-client_2.11-1.0.0-javadoc.jar
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-hash/swagger-java-client_2.11-1.0.0-sources.jar" -T publish/io/swagger/swagger-java-client_2.11/1.0.0/swagger-java-client_2.11-1.0.0-sources.jar

}

publish
# sbt publish publishes libs to Artifactory for the scala version sbt is running as.
# sbt +publish publishes libs to Artifactory for all scala versions listed in crossScalaVersions.
# We only do sbt publish here because Travis runs against 2.11 and 2.12 in separate jobs, so each one publishes its version to Artifactory.
#if [[ "$TRAVIS_PULL_REQUEST" == "false" && "$TRAVIS_BRANCH" == "develop" ]]; then
#if [[ "$TRAVIS_PULL_REQUEST" == "false" && "$TRAVIS_BRANCH" == "develop" ]]; then
#	sbt ++$TRAVIS_SCALA_VERSION publish -Dproject.isSnapshot=false
#else
#	sbt ++$TRAVIS_SCALA_VERSION publish -Dproject.isSnapshot=true
#fi