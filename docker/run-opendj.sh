#!/usr/bin/env bash

# check if mysql is running
CONTAINER=opendj
RUNNING=$(docker inspect -f {{.State.Running}} $CONTAINER || echo "false")

# mysql set-up
if ! $RUNNING; then
    echo "attempting to remove old opendj container..."
    docker rm -f $CONTAINER || echo "docker rm failed. nothing to rm."

    echo "starting up  container..."
    docker run --name $CONTAINER -e ROOTPASS="testtesttest" -e BASE_DN=dc=dsde-dev,dc=broadinstitute,dc=org -v ${PWD}/opendjsetup.sh:/opt/opendj/bootstrap/setup.sh -v ${PWD}/example-v1.json:/opt/example-v1.json -d -p 3389:389 broadinstitute/openam:opendj
    echo "sleeping 40 seconds til opendj is up and happy. This does not check anything."
    sleep 40
fi
