#!/bin/bash

# Render Azure test configs

VAULT_TOKEN=${1:-$(cat $HOME/.vault-token)}
DSDE_TOOLBOX_DOCKER_IMAGE=broadinstitute/dsde-toolbox:consul-0.20.0
VAULT_AZURE_MANAGED_APP_CLIENT_PATH=secret/dsde/terra/azure/common/managed-app-publisher
AZURE_MANAGED_APP_CLIENT_OUTPUT_FILE_PATH="$(dirname $0)"/src/test/resources/azure_managed_app_client.json
AZURE_PROPERTIES_OUTPUT_FILE_PATH="$(dirname $0)"/src/test/resources/application.conf

docker run --rm --cap-add IPC_LOCK \
            -e VAULT_TOKEN=$VAULT_TOKEN \
            ${DSDE_TOOLBOX_DOCKER_IMAGE} \
            vault read -format json ${VAULT_AZURE_MANAGED_APP_CLIENT_PATH} \
            | jq -r .data > ${AZURE_MANAGED_APP_CLIENT_OUTPUT_FILE_PATH}

AZURE_MANAGED_APP_CLIENT_ID=$(jq -r '."client-id"' ${AZURE_MANAGED_APP_CLIENT_OUTPUT_FILE_PATH})
AZURE_MANAGED_APP_CLIENT_SECRET=$(jq -r '."client-secret"' ${AZURE_MANAGED_APP_CLIENT_OUTPUT_FILE_PATH})
AZURE_MANAGED_APP_TENANT_ID=$(jq -r '."tenant-id"' ${AZURE_MANAGED_APP_CLIENT_OUTPUT_FILE_PATH})

# Note: the managed app plan id is hardcoded for now but should be updated once the Managed App
# definition is in Terraform. See: https://broadworkbench.atlassian.net/browse/TOAZ-28
cat > ${AZURE_PROPERTIES_OUTPUT_FILE_PATH} <<EOF
azureServices.azureEnabled="true"
azureServices.managedAppClientId=${AZURE_MANAGED_APP_CLIENT_ID}
azureServices.managedAppClientSecret=${AZURE_MANAGED_APP_CLIENT_SECRET}
azureServices.managedAppTenantId=${AZURE_MANAGED_APP_TENANT_ID}
azureServices.managedAppPlanId=terra-workspace-dev-plan
EOF

rm $AZURE_MANAGED_APP_CLIENT_OUTPUT_FILE_PATH

# Render Janitor test configs

VAULT_JANITOR_CLIENT_SA_PATH=secret/dsde/terra/kernel/integration/tools/crl_janitor/client-sa
JANITOR_CLIENT_SA_OUTPUT_FILE_PATH="$(dirname $0)"/src/test/resources/janitor-client-sa.json

docker run --rm --cap-add IPC_LOCK \
            -e VAULT_TOKEN=$VAULT_TOKEN \
            ${DSDE_TOOLBOX_DOCKER_IMAGE} \
            vault read -format json ${VAULT_JANITOR_CLIENT_SA_PATH} \
            | jq -r .data.key | base64 -d > ${JANITOR_CLIENT_SA_OUTPUT_FILE_PATH}
