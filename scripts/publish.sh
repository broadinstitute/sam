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

    COMMIT_HASH="${TRAVIS_COMMIT:0:7}"
    echo "Commit hash:$COMMIT_HASH"

    VERSION_HASH="1.0-$COMMIT_HASH"
    ORGANIZATION="org.broadinstitute.dsde"
    APP_NAME="sam"
    PATH="org/broadinstitute/dsde/sam_2.11/1.0.0/swagger-java-client_2.11-1.0.0"

    SCALA_11_VERSION="2.11.8"
    SCALA_12_VERSION="2.12.3"
    SCALA_VERSIONS="List(\"$SCALA_11_VERSION\", \"$SCALA_12_VERSION\")"

    sed -i "s|\$version|$VERSION_HASH|g" sam/swagger-client-build.txt
    sed -i "s|\$organization|$ORGANIZATION|g" sam/swagger-client-build.txt
    sed -i "s|\$name|$APP_NAME|g" sam/swagger-client-build.txt
    sed -i "s|\$scalaVersions|$SCALA_VERSIONS|g" sam/swagger-client-build.txt


    echo "adding plugins file..."
    cd generated
    mkdir project
#    echo "addSbtPlugin(\"com.eed3si9n\" % \"sbt-assembly\" % \"0.14.3\")" > project/plugins.sbt
#    sed -i 's/$version/$/g' build.sbt



#    sed -i '/organization := /c\    organization := \"org.broadinstitute.dsde\",' build.sbt
#    sed 's/.*organization := */organization := \"org.broadinstitute.dsde\",/'
#    sed 's/.*TEXT_TO_BE_REPLACED.*/This line is removed by the admin./'
#    sed -i '/name := /c\    name := \"sam\",' build.sbt
#    sed -i '/version := /c\    version := \"1.0.0\",' build.sbt
#    echo "print build.sbt"
#    cat build.sbt

    echo "compiling Java Client API..."
    sbt compile assembly publish
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
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-$COMMIT_HASH/swagger-java-client_2.11-1.0.0.jar" -T publish/org/broadinstitute/dsde/sam_2.11/1.0.0/swagger-java-client_2.11-1.0.0.jar
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-$COMMIT_HASH/swagger-java-client_2.11-1.0.0.pom" -T publish/org/broadinstitute/dsde/sam_2.11/1.0.0/swagger-java-client_2.11-1.0.0.pom
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-$COMMIT_HASH/swagger-java-client_2.11-1.0.0-javadoc.jar" -T publish/org/broadinstitute/dsde/sam_2.11/1.0.0/swagger-java-client_2.11-1.0.0-javadoc.jar
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/org/broadinstitute/dsde/sam_2.11/0.1-$COMMIT_HASH/swagger-java-client_2.11-1.0.0-sources.jar" -T publish/org/broadinstitute/dsde/sam_2.11/1.0.0/swagger-java-client_2.11-1.0.0-sources.jar

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