ENV=${1:-dev}
VAULT_TOKEN=${2:-$(cat "$HOME"/.vault-token)}

VAULT_ADDR="https://clotho.broadinstitute.org:8200"
SAM_VAULT_PATH="secret/dsde/firecloud/$ENV/sam"
SERVICE_OUTPUT_LOCATION="$(dirname "$0")/src/main/resources/rendered"

vault read --format=json --field=data ${SAM_VAULT_PATH}/sam-account.json > ${SERVICE_OUTPUT_LOCATION}/sam-account.json
vault read --field=private_key ${SAM_VAULT_PATH}/sam-account.json > ${SERVICE_OUTPUT_LOCATION}/sam-account.pem
vault read --format=json --field=data ${SAM_VAULT_PATH}/sam-firestore-account.json > ${SERVICE_OUTPUT_LOCATION}/sam-firestore-account.json

vault read --format=json --field=data ${SAM_VAULT_PATH}/service_accounts/service_account_0 > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-0.json
vault read --format=json --field=data ${SAM_VAULT_PATH}/service_accounts/service_account_1 > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-1.json
vault read --format=json --field=data ${SAM_VAULT_PATH}/service_accounts/service_account_2 > ${SERVICE_OUTPUT_LOCATION}/admin-service-account-2.json

