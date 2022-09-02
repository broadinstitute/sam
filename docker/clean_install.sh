#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

export SBT_OPTS="-Xms2g -Xmx2g"
sbt 'set test in assembly := {}' clean assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
