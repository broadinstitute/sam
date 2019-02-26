set -e

docker run --rm -v ${PWD}:/local openapitools/openapi-generator-cli generate -i /local/src/main/resources/swagger/api-docs.yaml -g java -o /local/codegen_java --api-package org.broadinstitute.dsde.workbench.client.sam.api --model-package org.broadinstitute.dsde.workbench.client.sam.model

cd codegen_java

# make sure it compiles
sbt compile