#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

# Tests are run in jenkins
export SBT_OPTS="-Xms2g -Xmx2g -Dpostgres.host=postgres -Dpostgres.port=5432"
source $SAM_DIR/env/test.env
sbt "testOnly --"
sbt assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
sbt clean
