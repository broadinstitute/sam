#!/bin/bash

set -e

sbt -J-Xms2g -J-Xmx2g -Ddirectory.url=$DIRECTORY_URL -Ddirectory.password=$DIRECTORY_PASSWORD "test-only -- -n org.broadinstitute.tags.SchemaInit"
