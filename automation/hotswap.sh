#!/usr/bin/env bash

# Updates the Leo jar of a FIAB to reflect current local code.
# Run using "./automation/hotswap.sh <your FIAB name>" at the root of the sam repo clone

set -eu

# see https://stackoverflow.com/questions/3601515/how-to-check-if-a-variable-is-set-in-bash
if [ -z ${1+x} ]; then
      echo "No arguments supplied. Please provide FIAB name as an argument."
      exit 1
fi

FIAB=$1
ENV=${2:-dev}

printf "Generating the Sam jar...\n\n"
sbt -Dsbt.log.noformat=true clean assembly
SAM_JAR_PATH=$(ls target/scala-2.12/sam-assembly*)

printf "\n\nJar successfully generated."

SAM_JAR_NAME=$(basename $SAM_JAR_PATH)
#
## Rename the jar to leonardo-assembly-0.1-437ee4a9-SNAPSHOT.jar so the fiab-start Jenkins job picks up the right jar file
#NEW_SAM_JAR_NAME=$(echo ${LEO_JAR_NAME}|sed 's/http\-/leonardo\-/g' )

printf "\n\nCopying ${SAM_JAR_PATH} to /tmp on FIAB '${FIAB}'...\n\n"
gcloud compute scp ${SAM_JAR_PATH} ${FIAB}:/tmp --zone=us-central1-a --project broad-dsde-${ENV}

printf "\n\nRemoving the old Leo jars on the FIAB...\n\n"
# For some reason including the rm command to be executed on the FIAB within the EOSSH block below doesn't work
gcloud compute ssh --project broad-dsde-${ENV} --zone us-central1-a ${FIAB} -- 'sudo docker exec -it firecloud_sam-app_1 sh -c "rm -f /sam/*jar"'

printf "\n\nCopying the Leo jar to the right location on the FIAB, and restarting the Leo app and proxy...\n\n"
gcloud compute ssh --project broad-dsde-${ENV} --zone us-central1-a ${FIAB} << EOSSH
    sudo docker cp /tmp/${SAM_JAR_NAME} firecloud_sam-app_1:/sam/${SAM_JAR_NAME}
    sudo docker restart firecloud_sam-app_1
    sudo docker restart firecloud_sam-proxy_1
EOSSH
