set -e

docker run --rm -v ${PWD}:/local openapitools/openapi-generator-cli:v4.0.3 generate -i /local/src/main/resources/swagger/api-docs.yaml -g java -o /local/codegen_java --api-package org.broadinstitute.dsde.workbench.client.sam.api --model-package org.broadinstitute.dsde.workbench.client.sam.model
echo ranDockerCommand
cd codegen_java
echo attemptingTesting
sbt test
echo finishedTesting
