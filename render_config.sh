SERVICE_OUTPUT_LOCATION="$(dirname "$0")/src/main/resources/rendered"
SECRET_ENV_VARS_LOCATION="${SERVICE_OUTPUT_LOCATION}/secrets.env"

gcloud container clusters get-credentials --zone us-central1-a --project broad-dsde-dev terra-dev

kubectl -n terra-dev get secret sam-sa-secret -o 'go-template={{index .data "sam-account.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/sam-account.json
kubectl -n terra-dev get secret sam-sa-secret -o 'go-template={{index .data "sam-account.pem"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/sam-account.pem

kubectl -n terra-dev get secret admin-one-sa-secret -o 'go-template={{index .data "admin-service-account-1.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-1.json
kubectl -n terra-dev get secret admin-two-sa-secret -o 'go-template={{index .data "admin-service-account-2.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-2.json
kubectl -n terra-dev get secret admin-three-sa-secret -o 'go-template={{index .data "admin-service-account-3.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-3.json
kubectl -n terra-dev get secret admin-four-sa-secret -o 'go-template={{index .data "admin-service-account-4.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-4.json
kubectl -n terra-dev get secret admin-five-sa-secret -o 'go-template={{index .data "admin-service-account-5.json"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-5.json

kubectl -n terra-dev get configmap sam-oauth2-configmap -o 'go-template={{index .data "oauth2-config"}}' > ${SERVICE_OUTPUT_LOCATION}/oauth2.conf
# Local dev uses a macOS-specific docker replacement hostname for locahost, so replace all instances in the proxy config.
kubectl -n terra-dev get configmap sam-proxy-configmap -o 'go-template={{index .data "apache-httpd-proxy-config"}}' | sed 's/localhost/host\.docker\.internal/g' > ${SERVICE_OUTPUT_LOCATION}/site.conf

kubectl -n local-dev get secrets local-dev-cert -o 'go-template={{index .data "tls.crt"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/server.crt
kubectl -n local-dev get secrets local-dev-cert -o 'go-template={{index .data "tls.key"}}' | base64 --decode > ${SERVICE_OUTPUT_LOCATION}/server.key

if [ -f "${SECRET_ENV_VARS_LOCATION}" ]; then
  rm "${SECRET_ENV_VARS_LOCATION}"
fi

{
echo export AZURE_MANAGED_APP_CLIENT_ID="$(gcloud secrets versions access latest --project="broad-dsde-dev" --secret="sam-managed-app-publisher-creds" | jq -r '."client-id"')";
echo export AZURE_MANAGED_APP_CLIENT_SECRET="$(gcloud secrets versions access latest --project="broad-dsde-dev" --secret="sam-managed-app-publisher-creds" | jq -r '."client-secret"')";
echo export AZURE_MANAGED_APP_TENANT_ID="$(gcloud secrets versions access latest --project="broad-dsde-dev" --secret="sam-managed-app-publisher-creds" | jq -r '."tenant-id"')";
echo export LEGACY_GOOGLE_CLIENT_ID="$(gcloud secrets versions access latest --project="broad-dsde-dev" --secret="refresh-token-oauth-credential" | jq -r '.web.client_id')";
echo export OIDC_CLIENT_ID="$(gcloud secrets versions access latest --project="broad-dsde-dev" --secret="b2c-application-id" | jq -r '.value')";

echo export SERVICE_ACCOUNT_CLIENT_EMAIL="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .client_email)";
echo export SERVICE_ACCOUNT_CLIENT_ID="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .client_id)";
echo export SERVICE_ACCOUNT_CLIENT_PROJECT_ID="$(cat ${SERVICE_OUTPUT_LOCATION}/sam-account.json | jq .project_id)";
} >> "${SECRET_ENV_VARS_LOCATION}"
