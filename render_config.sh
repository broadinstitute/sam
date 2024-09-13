ENV=${1:-dev}
SERVICE_OUTPUT_LOCATION="$(dirname "$0")/src/main/resources/rendered"
SECRET_ENV_VARS_LOCATION="${SERVICE_OUTPUT_LOCATION}/secrets.env"

gcloud container clusters get-credentials --zone us-central1-a --project broad-dsde-"${ENV}" terra-"${ENV}"

kubectl -n terra-"${ENV}" get secret sam-sa-secret -o 'go-template={{index .data "sam-account.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/sam-account.json
kubectl -n terra-"${ENV}" get secret sam-sa-secret -o 'go-template={{index .data "sam-account.pem"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/sam-account.pem

kubectl -n terra-"${ENV}" get secret admin-one-sa-secret -o 'go-template={{index .data "admin-service-account-1.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-1.json
kubectl -n terra-"${ENV}" get secret admin-two-sa-secret -o 'go-template={{index .data "admin-service-account-2.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-2.json
kubectl -n terra-"${ENV}" get secret admin-three-sa-secret -o 'go-template={{index .data "admin-service-account-3.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-3.json
kubectl -n terra-"${ENV}" get secret admin-four-sa-secret -o 'go-template={{index .data "admin-service-account-4.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-4.json
kubectl -n terra-"${ENV}" get secret admin-five-sa-secret -o 'go-template={{index .data "admin-service-account-5.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-5.json

kubectl -n terra-"${ENV}" get configmap sam-oauth2-configmap -o 'go-template={{index .data "oauth2-config"}}' > ${SERVICE_OUTPUT_LOCATION}/oauth2.conf
# Local dev uses a macOS-specific docker replacement hostname for localhost, so replace all instances in the proxy config.
kubectl -n terra-"${ENV}" get configmap sam-proxy-configmap -o 'go-template={{index .data "apache-httpd-proxy-config"}}' | sed 's/localhost/host\.docker\.internal/g' > ${SERVICE_OUTPUT_LOCATION}/site.conf

gcloud container clusters get-credentials --zone us-central1-a --project broad-dsde-dev terra-dev
kubectl -n local-dev get secrets local-dev-cert -o 'go-template={{index .data "tls.crt"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/server.crt
kubectl -n local-dev get secrets local-dev-cert -o 'go-template={{index .data "tls.key"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/server.key

if [ -f "${SECRET_ENV_VARS_LOCATION}" ]; then
  rm "${SECRET_ENV_VARS_LOCATION}"
fi

{
echo export AZURE_MANAGED_APP_CLIENT_ID="$(vault read -field=client-id secret/dsde/terra/azure/"${ENV}"/sam/managed-app-publisher)";
echo export AZURE_MANAGED_APP_CLIENT_SECRET="$(vault read -field=client-secret secret/dsde/terra/azure/"${ENV}"/sam/managed-app-publisher)";
echo export AZURE_MANAGED_APP_TENANT_ID="$(vault read -field=tenant-id secret/dsde/terra/azure/"${ENV}"/sam/managed-app-publisher)";
echo export LEGACY_GOOGLE_CLIENT_ID="$(vault read -format=json -field=data secret/dsde/firecloud/"${ENV}"/common/refresh-token-oauth-credential.json | jq -r '.web.client_id')";
echo export OIDC_CLIENT_ID="$(vault read -field=value secret/dsde/terra/azure/"${ENV}"/b2c/application_id)";

echo export SERVICE_ACCOUNT_CLIENT_EMAIL="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .client_email)";
echo export SERVICE_ACCOUNT_CLIENT_ID="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .client_id)";
echo export SERVICE_ACCOUNT_CLIENT_PROJECT_ID="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .project_id)";
} >> "${SECRET_ENV_VARS_LOCATION}"
