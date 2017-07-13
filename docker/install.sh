#!/bin/bash

set -e

SAM_DIR=$1
cd $SAM_DIR

if [ -e "jenkins_env.sh" ]; then
	source "jenkins_env.sh"
fi

# Tests are run in jenkins which has a custom mysql instance just for testing
sbt -J-Xms4g -J-Xmx4g test -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD
sbt -J-Xms4g -J-Xmx4g assembly
SAM_JAR=$(find target | grep 'sam.*\.jar')
mv $SAM_JAR .
sbt clean
