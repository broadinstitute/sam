ENV=${1:-dev}
VAULT_TOKEN=${2:-$(cat "$HOME"/.vault-token)}

VAULT_ADDR="https://clotho.broadinstitute.org:8200"
SAM_VAULT_PATH="secret/dsde/firecloud/$ENV/sam"
SERVICE_OUTPUT_LOCATION="$(dirname "$0")/src/main/resources/rendered"
SECRET_ENV_VARS_LOCATION="${SERVICE_OUTPUT_LOCATION}/secrets.env"

gcloud container clusters get-credentials --zone us-central1-a --project broad-dsde-dev terra-dev

kubectl -n terra-dev get secret sam-sa-secret -o 'go-template={{index .data "sam-account.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/sam-account.json
kubectl -n terra-dev get secret sam-sa-secret -o 'go-template={{index .data "sam-account.pem"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/sam-account.pem

kubectl -n terra-dev get secret admin-sa-secret -o 'go-template={{index .data "admin-service-account-0.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-0.json
kubectl -n terra-dev get secret admin-one-sa-secret -o 'go-template={{index .data "admin-service-account-1.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-1.json
kubectl -n terra-dev get secret admin-two-sa-secret -o 'go-template={{index .data "admin-service-account-2.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-2.json
kubectl -n terra-dev get secret admin-three-sa-secret -o 'go-template={{index .data "admin-service-account-3.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-3.json
kubectl -n terra-dev get secret admin-four-sa-secret -o 'go-template={{index .data "admin-service-account-4.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-4.json

if [ -f "${SECRET_ENV_VARS_LOCATION}" ]; then
  rm "${SECRET_ENV_VARS_LOCATION}"
fi

{
echo export AZURE_MANAGED_APP_CLIENT_ID="$(vault read -field=client-id secret/dsde/terra/azure/dev/sam/managed-app-publisher)";
echo export AZURE_MANAGED_APP_CLIENT_SECRET="$(vault read -field=client-secret secret/dsde/terra/azure/dev/sam/managed-app-publisher)";
echo export AZURE_MANAGED_APP_TENANT_ID="$(vault read -field=tenant-id secret/dsde/terra/azure/dev/sam/managed-app-publisher)";
echo export LEGACY_GOOGLE_CLIENT_ID="$(vault read -format=json -field=data secret/dsde/firecloud/dev/common/refresh-token-oauth-credential.json | jq -r '.web.client_id')";
echo export OIDC_CLIENT_ID="$(vault read -field=value secret/dsde/terra/azure/dev/b2c/application_id)";

echo export SERVICE_ACCOUNT_CLIENT_EMAIL="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .client_email)";
echo export SERVICE_ACCOUNT_CLIENT_ID="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .client_id)";
echo export SERVICE_ACCOUNT_CLIENT_PROJECT_ID="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .project_id)";
} >> "${SECRET_ENV_VARS_LOCATION}"
