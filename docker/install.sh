#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

# Tests are run in jenkins which has a custom opendj instance just for testing
SBT_OPTS="-J-Xms2g -J-Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD" sbt "testOnly -- -l org.broadinstitute.tags.SchemaInit"
SBT_OPTS="-J-Xms2g -J-Xmx2g" sbt assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
sbt clean
