#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

# Tests are run in jenkins which has a custom opendj instance just for testing
export SBT_OPTS="-Xms2g -Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD"
sbt "testOnly -- -l org.broadinstitute.tags.SchemaInit"
sbt assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
sbt clean
