#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

export SBT_OPTS="-Xms2g -Xmx2g"
source $SAM_DIR/env/test.env
sbt 'set assembly / test := {}' clean
