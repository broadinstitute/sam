#!/bin/bash

# This script runs sbt assembly to produce a target jar file.
# Used by build_jar.sh
#
set -e

SAM_DIR=$1
cd $SAM_DIR

export SBT_OPTS="-Xms2g -Xmx2g"
sbt 'set assembly / test := {}' clean assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
