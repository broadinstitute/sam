#!/bin/bash

# Get script location, via https://stackoverflow.com/a/12197227
pushd . > /dev/null
WORKING_DIR="${BASH_SOURCE[0]}";
  while([ -h "${WORKING_DIR}" ]); do
    cd "`dirname "${WORKING_DIR}"`"
    WORKING_DIR="$(readlink "`basename "${WORKING_DIR}"`")";
  done
cd "`dirname "${WORKING_DIR}"`" > /dev/null
WORKING_DIR="`pwd`";
popd  > /dev/null

if [ -z ${1+x} ]; then
  printf "Must specify where Firecloud is running: 'fiab', 'local', 'alpha', 'prod', or ip.\n"
  exit 1
fi

# Defaults
ENV=dev
NUM_NODES=2
TEST_ENTRYPOINT="testOnly -- -l ProdTest"
TEST_CONTAINER="automation-$(head /dev/urandom | env LC_CTYPE=C tr -dc a-z0-9 | head -c 8)"
DOCKERHOST_ADDRESS=$(docker network inspect docker_default -f='{{(index .IPAM.Config 0).Gateway}}') # Gets address of real localhost from within docker network

# Parameters
FC_INSTANCE=${1}
ENV=${2:-$ENV}
VAULT_TOKEN=${3:-$(cat ~/.vault-token)}

export FC_INSTANCE WORKING_DIR ENV

if [ "$FC_INSTANCE" = "local" ] || [ "$FC_INSTANCE" = "fiab" ]; then
  FC_INSTANCE="$(cat /etc/hosts | grep -v '#' | grep 'firecloud-fiab.dsde-dev.broadinstitute.org' | awk '{print $1}')"
fi

if [ ${1} = "local" ]; then
  UI_LOCATION=$DOCKERHOST_ADDRESS
else
  UI_LOCATION=$FC_INSTANCE
fi

if [ "$ENV" = "prod" ]; then
  TEST_ENTRYPOINT="testOnly -- -n ProdTest"
fi

if [ "$FC_INSTANCE" = "alpha" ] || [ "$FC_INSTANCE" = "prod" ]; then
  HUB_COMPOSE=hub-compose.yml
else
  HOST_MAPPING="--add-host=firecloud-fiab.dsde-${ENV}.broadinstitute.org:${UI_LOCATION} --add-host=firecloud-orchestration-fiab.dsde-${ENV}.broadinstitute.org:${FC_INSTANCE} --add-host=rawls-fiab.dsde-${ENV}.broadinstitute.org:${FC_INSTANCE} --add-host=thurloe-fiab.dsde-${ENV}.broadinstitute.org:${FC_INSTANCE} --add-host=sam-fiab.dsde-${ENV}.broadinstitute.org:${FC_INSTANCE} -e SLACK_API_TOKEN=$SLACK_API_TOKEN -e BUILD_NUMBER=$BUILD_NUMBER -e SLACK_CHANNEL=${SLACK_CHANNEL}"
  HUB_COMPOSE=hub-compose-fiab.yml
fi

HUB_COMPOSE="$WORKING_DIR/$HUB_COMPOSE"

printf "Building test image..."
docker build -f $WORKING_DIR/../Dockerfile-tests -t $TEST_CONTAINER $WORKING_DIR/..

cleanup () {
  # kill and remove any running containers
  printf "\n"
  docker-compose -f ${HUB_COMPOSE} stop
  docker stop "$TEST_CONTAINER"
  docker image rm -f "$TEST_CONTAINER"
  trap - EXIT HUP INT QUIT PIPE TERM 0 20
}

cleanup-error () {
  cleanup
  printf "$(tput setaf 1)Tests Stopped For Unexpected Reasons$(tput setaf 0)\n"
}

# catch unexpected failures, do cleanup and output an error message
trap cleanup-error EXIT HUP INT QUIT PIPE TERM 0 20

printf "FIRECLOUD LOCATION: $FC_INSTANCE\n"
docker-compose -f ${HUB_COMPOSE} pull
docker-compose -f ${HUB_COMPOSE} up -d
docker-compose -f ${HUB_COMPOSE} scale chrome=$NUM_NODES

# build and run the composed services
if [ $? -ne 0 ]; then
  printf "$(tput setaf 1)Docker Compose Failed$(tput setaf 0)\n"
  exit -1
fi

# Make sure ${WORKING_DIR}/chrome/downloads exists and make it writable by the node-chrome containers.
mkdir -p $WORKING_DIR/chrome/downloads
# Without this, the directory permissions don't allow chrome to automatically save downloads which
# leads to a system save dialog opening which Selenium doesn't have any way of handling.
echo '--- Begin ugly but necessary python stack trace and error "ValueError" that looks bad but actually does something useful ---'
docker-compose -f ${HUB_COMPOSE} exec chrome sudo chmod 777 /app/chrome/downloads
echo '--- End ugly but necessary python error ---'

# render ctmpls
docker pull broadinstitute/dsde-toolbox:dev
docker run --rm -e VAULT_TOKEN=${VAULT_TOKEN} \
    -e ENVIRONMENT=${ENV} -e ROOT_DIR=/app -v ${WORKING_DIR}:/working \
    -e OUT_PATH=/working/target -e INPUT_PATH=/working -e LOCAL_UI=false \
    broadinstitute/dsde-toolbox:dev render-templates.sh


# run tests
docker run -e FC_INSTANCE=$FC_INSTANCE \
    --net=docker_default \
    -e ENV=$ENV \
    -P --rm -t -e CHROME_URL="http://hub:4444/" ${HOST_MAPPING} \
    -v $WORKING_DIR/target/application.conf:/app/src/test/resources/application.conf \
    -v $WORKING_DIR/target/firecloud-account.pem:/app/src/test/resources/firecloud-account.pem \
    -v $WORKING_DIR/target/users.json:/app/src/test/resources/users.json \
    -v $WORKING_DIR/chrome/downloads:/app/chrome/downloads \
    -v $WORKING_DIR/failure_screenshots:/app/failure_screenshots \
    -v $WORKING_DIR/output:/app/output \
    -v jar-cache:/root/.ivy -v jar-cache:/root/.ivy2 \
    --link docker_hub_1:hub --name ${TEST_CONTAINER} -w /app \
    ${TEST_CONTAINER} "${TEST_ENTRYPOINT}"


# Grab exit code
TEST_EXIT_CODE=$?


# inspect the output of the test and display respective message
if [ -z ${TEST_EXIT_CODE+x} ] || [ $TEST_EXIT_CODE -ne 0 ]; then
  printf "$(tput setaf 1)Tests Failed$(tput setaf 0) - Exit Code: $TEST_EXIT_CODE\n"
else
  printf "$(tput setaf 2)Tests Passed$(tput setaf 0)\n"
fi

# call the cleanup function
cleanup

# exit the script with the same code as the test service code
exit $TEST_EXIT_CODE
