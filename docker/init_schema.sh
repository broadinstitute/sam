#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

export SBT_OPTS="-Xms2g -Xmx2g -Dpostgres.host=postgres -Dpostgres.port=5432"
source $SAM_DIR/env/test.env
sbt "testOnly --"
