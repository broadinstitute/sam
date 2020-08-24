#!/usr/bin/env bash

SBT_CMD=${1-"testOnly -- -l ProdTest"}
echo $SBT_CMD

set -o pipefail

sbt -Dheadless=true "${SBT_CMD}"
TEST_EXIT_CODE=$?
sbt clean

if [[ $TEST_EXIT_CODE != 0 ]]; then exit $TEST_EXIT_CODE; fi
