#!/usr/bin/env bash

# Defaults
WORKING_DIR=$PWD
VAULT_TOKEN=$(cat ~/.vault-token)
ENV=dev
LOCAL_UI=false

# Parameters
WORKING_DIR=${1:-$WORKING_DIR}
VAULT_TOKEN=${2:-$VAULT_TOKEN}
ENV=${3:-$ENV}
if [ "$4" = "local_ui" ]; then
  LOCAL_UI=true 
fi


# render ctmpls
echo "$WORKING_DIR"
docker pull broadinstitute/dsde-toolbox:dev
docker run --rm -e VAULT_TOKEN=${VAULT_TOKEN} \
    -e ENVIRONMENT=${ENV} -e ROOT_DIR=${WORKING_DIR} -v ${WORKING_DIR}:/working \
    -e OUT_PATH=/working/src/test/resources -e INPUT_PATH=/working/docker -e LOCAL_UI=$LOCAL_UI \
    broadinstitute/dsde-toolbox:dev render-templates.sh
