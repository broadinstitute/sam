ENV=${1:-dev}
VAULT_TOKEN=${2:-$(cat "$HOME"/.vault-token)}

VAULT_ADDR="https://clotho.broadinstitute.org:8200"
SAM_VAULT_PATH="secret/dsde/firecloud/$ENV/sam"
SERVICE_OUTPUT_LOCATION="$(dirname "$0")/src/main/resources/rendered"
SECRET_ENV_VARS_LOCATION="${SERVICE_OUTPUT_LOCATION}/secrets.env"

vault read --format=json --field=data ${SAM_VAULT_PATH}/sam-account.json > ${SERVICE_OUTPUT_LOCATION}/sam-account.json
vault read --field=private_key ${SAM_VAULT_PATH}/sam-account.json > ${SERVICE_OUTPUT_LOCATION}/sam-account.pem
vault read --format=json --field=data ${SAM_VAULT_PATH}/sam-firestore-account.json > ${SERVICE_OUTPUT_LOCATION}/sam-firestore-account.json

vault read --format=json --field=data ${SAM_VAULT_PATH}/service_accounts/service_account_0 > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-0.json
vault read --format=json --field=data ${SAM_VAULT_PATH}/service_accounts/service_account_1 > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-1.json
vault read --format=json --field=data ${SAM_VAULT_PATH}/service_accounts/service_account_2 > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-2.json


if [ -f "${SECRET_ENV_VARS_LOCATION}" ]; then
  rm "${SECRET_ENV_VARS_LOCATION}"
fi

{
echo export AZURE_MANAGED_APP_CLIENT_ID="$(vault read -field=client-id secret/dsde/terra/azure/dev/sam/managed-app-publisher)";
echo export AZURE_MANAGED_APP_CLIENT_SECRET="$(vault read -field=client-secret secret/dsde/terra/azure/dev/sam/managed-app-publisher)";
echo export AZURE_MANAGED_APP_TENANT_ID="$(vault read -field=tenant-id secret/dsde/terra/azure/dev/sam/managed-app-publisher)";
echo export LEGACY_GOOGLE_CLIENT_ID="$(vault read -format=json -field=data secret/dsde/firecloud/dev/common/refresh-token-oauth-credential.json | jq -r '.web.client_id')";
echo export OIDC_CLIENT_ID="$(vault read -field=value secret/dsde/terra/azure/dev/b2c/application_id)";
echo export SERVICE_ACCOUNT_CLIENT_EMAIL="$(vault read -field=client_email secret/dsde/firecloud/dev/sam/sam-account.json)";
echo export SERVICE_ACCOUNT_CLIENT_ID="$(vault read -field=client_id secret/dsde/firecloud/dev/sam/sam-account.json)";
echo export SERVICE_ACCOUNT_CLIENT_PROJECT_ID="$(vault read -field=project_id secret/dsde/firecloud/dev/sam/sam-account.json)";
} >> "${SECRET_ENV_VARS_LOCATION}"
