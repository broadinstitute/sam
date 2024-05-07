set -e

docker run --rm -v ${PWD}:/local openapitools/openapi-generator-cli:v7.3.0 generate -i /local/src/main/resources/swagger/api-docs.yaml -g java -o /local/codegen_java --api-package org.broadinstitute.dsde.workbench.client.sam.api --model-package org.broadinstitute.dsde.workbench.client.sam.model --template-dir /local/codegen_java/templates --library okhttp-gson --additional-properties useJakartaEe=true,disallowAdditionalPropertiesIfNotPresent=false,isOAuth=true
cd codegen_java
sbt test
