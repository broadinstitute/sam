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
    PATH_PREFIX="org/broadinstitute/dsde/$APP_NAME"
    APP_NAME="sam"
    SCALA_11_VERSION="2.11.8"
    SCALA_11_VERSION_SHORT="${SCALA_11_VERSION:0:3}"
    SCALA_12_VERSION="2.12.3"
    SCALA_12_VERSION_SHORT="${SCALA_12_VERSION:0:3}"
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

    echo "pushing to Artifactory..."

    chmod a+wx publish

    declare -a SCALA_VERSIONS_ARRAY=($SCALA_11_VERSION_SHORT $SCALA_12_VERSION_SHORT)
    declare -a SUFFIX_ARRAY=(".jar" ".pom" "-javadoc.jar" "-sources.jar")

    for scala_version in "${SCALA_VERSIONS_ARRAY[@]}"
      do
        find publish/${PATH_PREFIX}_$scala_version/$VERSION_HASH -name "*.md5" -type f -delete
        find publish/${PATH_PREFIX}_$scala_version/$VERSION_HASH -name "*.sha1" -type f -delete

        ARTIFACTORY_PATH="${PATH_PREFIX}_$scala_version/$VERSION_HASH/${APP_NAME}_$scala_version-$VERSION_HASH"

        for suffix in "${SUFFIX_ARRAY[@]}"
          do
            curl -u $ARTIF_USER:$ARTIF_PASSWORD -X PUT "https://broadinstitute.jfrog.io/broadinstitute/libs-release-local/$ARTIFACTORY_PATH$suffix" -T publish/$ARTIFACTORY_PATH$suffix
          done
      done

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