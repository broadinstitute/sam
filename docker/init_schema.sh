#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

export SBT_OPTS="-Xms2g -Xmx2g -Dpostgres.host=postgres -Dpostgres.port=5432"
sbt "testOnly -- -n org.broadinstitute.tags.SchemaInit"
