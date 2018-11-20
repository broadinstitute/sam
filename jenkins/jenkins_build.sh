#!/bin/bash


set -eux
SVCACCT_FILE="dspci-wb-gcr-service-account.json"
GCR_SVCACCT_VAULT="secret/dsde/dsp-techops/common/$SVCACCT_FILE"
VAULT_TOKEN=${VAULT_TOKEN:-$(cat /etc/vault-token-dsde)}

docker run --rm  -e VAULT_TOKEN=$VAULT_TOKEN \
   broadinstitute/dsde-toolbox:latest vault read --format=json ${GCR_SVCACCT_VAULT} \
   | jq .data > ${SVCACCT_FILE}

./docker/build.sh jar -d push -g gcr.io/broad-dsp-gcr-public/${PROJECT} -k ${SVCACCT_FILE}

# clean up
rm -f ${SVCACCT_FILE}
