#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

sbt -J-Xms2g -J-Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD "test-only -- -n org.broadinstitute.tags.SchemaInit"
