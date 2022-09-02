#!/bin/bash

set -e

# Get the last commit hash of the model directory and set it as an environment variable
GIT_MODEL_HASH=$(git log -n 1 --pretty=format:%h)

docker run --rm -v $PWD:/working -v jar-cache:/root/.ivy -v jar-cache:/root/.ivy2 broadinstitute/scala-baseimage:jdk11-2.13.5-1.4.7 /working/docker/clean_install.sh /working
EXIT_CODE=$?

if [ $EXIT_CODE != 0 ]; then
    echo "jar build exited with status $EXIT_CODE"
    exit $EXIT_CODE
fi
