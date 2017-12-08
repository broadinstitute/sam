#!/usr/bin/env bash

set -e

timestamp() {
  date +"%T"
}

function publish ()
{
    echo "getting swagger codegen jar..."
    wget http://central.maven.org/maven2/io/swagger/swagger-codegen-cli/2.2.3/swagger-codegen-cli-2.2.3.jar -O swagger-codegen-cli.jar
    echo "generating Sam Java Client API..."
    java -jar swagger-codegen-cli.jar generate -l java --input-spec ./src/main/resources/swagger/api-docs.yaml --output generated


    COMMIT_HASH="${TRAVIS_COMMIT:0:7}"
    echo "Commit hash:$COMMIT_HASH"
    VERSION_HASH="1.0-$COMMIT_HASH"
    ORGANIZATION="org.broadinstitute.dsde"
    APP_NAME="sam"
    SCALA_11_VERSION="2.11.8"
    SCALA_12_VERSION="2.12.3"
    SCALA_VERSIONS="List(\"$SCALA_11_VERSION\", \"$SCALA_12_VERSION\")"
    PATH_SCALA_11="org/broadinstitute/dsde/sam_2.11/$VERSION_HASH/swagger-java-client_2.11-$VERSION_HASH"
    PATH_SCALA_12="org/broadinstitute/dsde/sam_2.12/$VERSION_HASH/swagger-java-client_2.12-$VERSION_HASH"

    rm generated/build.sbt
    cp -f swagger-client-build.txt generated/build.sbt
    cd generated
    echo "look at generated"
    ls
    sed -i "s|\$version|$VERSION_HASH|g" build.sbt
    sed -i "s|\$organization|$ORGANIZATION|g" build.sbt
    sed -i "s|\$name|$APP_NAME|g" build.sbt
    echo "read build"
    cat build.sbt
    sed -i "s|\$scalaVersions|$SCALA_VERSIONS|g" build.sbt

    echo "read build again"
    cat build.sbt

    echo "adding plugins file..."
    mkdir project
    echo "addSbtPlugin(\"com.eed3si9n\" % \"sbt-assembly\" % \"0.14.3\")" > project/plugins.sbt


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

    echo "what's in publish"
    ls publish
    echo "what's in publish/org"
    ls publish/org
    echo "what's in publish/org/broadinstitute"
    ls publish/org/broadinstitute
    echo "what's in publish/org/broadinstitute/dsde"
    ls publish/org/broadinstitute/dsde
    echo "what's in publish/org/broadinstitute/dsde/swagger-java-client_2.11"
    ls publish/org/broadinstitute/dsde/swagger-java-client_2.11
    echo "what's in publish/org/broadinstitute/dsde/swagger-java-client_2.12"
    ls publish/org/broadinstitute/dsde/swagger-java-client_2.12
    echo "what's in publish/org/broadinstitute/dsde/swagger-java-client_2.11/version"
    ls publish/org/broadinstitute/dsde/swagger-java-client_2.11/$VERSION_HASH
    echo "what's in publish/org/broadinstitute/dsde/swagger-java-client_2.12/version"
    ls publish/org/broadinstitute/dsde/swagger-java-client_2.12/$VERSION_HASH

    find publish/org/dsde/swagger-java-client_2.11/$VERSION_HASH -name "*.md5" -type f -delete
    find publish/org/dsde/swagger-java-client_2.11/$VERSION_HASH -name "*.sha1" -type f -delete
    find publish/org/dsde/swagger-java-client_2.12/$VERSION_HASH -name "*.md5" -type f -delete
    find publish/org/dsde/swagger-java-client_2.12/$VERSION_HASH -name "*.sha1" -type f -delete

    echo "pushing to Artifactory..."
    printenv
    echo "ls publish/io/swagger/swagger-java-client_2.11/1.0.0"
    ls publish/org/swagger/swagger-java-client_2.11/1.0.0
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