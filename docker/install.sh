#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

# Tests are run in jenkins which has a custom opendj instance just for testing
sbt -J-Xms2g -J-Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD "test-only -- -n org.broadinstitute.tags.SchemaInit"
docker restart opendj
sbt -J-Xms2g -J-Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD "test-only -- -l org.broadinstitute.tags.SchemaInit"
sbt -J-Xms2g -J-Xmx2g assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
sbt clean
