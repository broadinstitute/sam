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
    VERSION_HASH="1.0-$COMMIT_HASH"
    ORGANIZATION="org.broadinstitute.dsde"

    APP_NAME="sam"
    SCALA_11_VERSION="2.11.8"
    SCALA_12_VERSION="2.12.3"
    SCALA_VERSIONS="List(\"$SCALA_11_VERSION\", \"$SCALA_12_VERSION\")"

    rm generated/build.sbt
    cp -f swagger-client-build.txt generated/build.sbt
    cd generated

    sed -i "s|\$version|$VERSION_HASH|g" build.sbt
    sed -i "s|\$organization|$ORGANIZATION|g" build.sbt
    sed -i "s|\$name|$APP_NAME|g" build.sbt
    sed -i "s|\$scalaVersions|$SCALA_VERSIONS|g" build.sbt

    echo "adding plugins file..."
    mkdir project
    echo "addSbtPlugin(\"com.eed3si9n\" % \"sbt-assembly\" % \"0.14.3\")" > project/plugins.sbt

    echo "compiling Java Client API..."
    sbt compile assembly +publish

    echo "what's in publish/org/broadinstitute/dsde/sam_2.11/version"
    ls publish/org/broadinstitute/dsde/sam_2.11/$VERSION_HASH
    echo "what's in publish/org/broadinstitute/dsde/sam_2.12/version"
    ls publish/org/broadinstitute/dsde/sam_2.12/$VERSION_HASH

    find publish/org/broadinstitute/dsde/sam_2.11/$VERSION_HASH -name "*.md5" -type f -delete
    find publish/org/broadinstitute/dsde/sam_2.11/$VERSION_HASH -name "*.sha1" -type f -delete
    find publish/org/broadinstitute/dsde/sam_2.12/$VERSION_HASH -name "*.md5" -type f -delete
    find publish/org/broadinstitute/dsde/sam_2.12/$VERSION_HASH -name "*.sha1" -type f -delete

    echo "what's in publish/org/broadinstitute/dsde/sam_2.11/version post removal"
    ls publish/org/broadinstitute/dsde/sam_2.11/$VERSION_HASH
    echo "what's in publish/org/broadinstitute/dsde/sam_2.12/version post removal"
    ls publish/org/broadinstitute/dsde/sam_2.12/$VERSION_HASH

    echo "pushing to Artifactory..."
    PATH_SCALA_11="org/broadinstitute/dsde/$APP_NAME_2.11/$VERSION_HASH/$APP_NAME_2.11-$VERSION_HASH"
    PATH_SCALA_12="org/broadinstitute/dsde/$APP_NAME_2.12/$VERSION_HASH/$APP_NAME_2.12-$VERSION_HASH"

    chmod a+wx publish

#    curl -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-snapshot-build.timestamp=" + timestamp -T Desktop/myNewFile.txt
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/$PATH_SCALA_11.jar" -T publish/$PATH_SCALA_11.jar
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/$PATH_SCALA_11.pom" -T publish/$PATH_SCALA_11.pom
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/$PATH_SCALA_11-javadoc.jar" -T publish/$PATH_SCALA_11-javadoc.jar
    curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/$PATH_SCALA_11-sources.jar" -T publish/$PATH_SCALA_11-sources.jar

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