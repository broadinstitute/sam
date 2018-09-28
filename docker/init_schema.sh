#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

SBT_OPTS="-J-Xms2g -J-Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD" sbt "testOnly -- -n org.broadinstitute.tags.SchemaInit"
