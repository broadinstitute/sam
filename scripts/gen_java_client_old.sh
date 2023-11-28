set -e

docker run --rm -v ${PWD}:/local openapitools/openapi-generator-cli:v5.4.0 generate -i /local/src/main/resources/swagger/api-docs.yaml -g java -o /local/codegen_java_old --api-package org.broadinstitute.dsde.workbench.client.sam.api --model-package org.broadinstitute.dsde.workbench.client.sam.model --template-dir /local/codegen_java_old/templates --library okhttp-gson
cd codegen_java_old
sbt test
