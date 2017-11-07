#!/usr/bin/env bash

start()  {

    PORT=3389:389
    CONTAINER_NAME=opendj
    if [ $1 = "jenkins" ]; then
        PORT=389
        CONTAINER_NAME=$(opendj-$(date +"%y%m%d_%H%M%S"))
    fi
    echo "starting up container $CONTAINER_NAME..."
    docker run --name $CONTAINER_NAME -e ROOTPASS="testtesttest" -e BASE_DN=dc=dsde-dev,dc=broadinstitute,dc=org -v ${PWD}/docker/opendjsetup.sh:/opt/opendj/bootstrap/setup.sh -v ${PWD}/docker/example-v1.json:/opt/example-v1.json -d -p $PORT broadinstitute/openam:opendj
    echo "sleeping 40 seconds til opendj is up and happy. This does not check anything."
    sleep 40
}

stop() {
    echo "stopping $1"
    docker stop $1 || echo "$1 stop failed. container already stopped."
    docker rm -v $1 || echo "$1 rm -v failed.  container already destroyed."

}

COMMAND=$1
ENV=${2:-local}
NAME=${3:-opendj}
if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start"
    exit 1
fi

if [ $COMMAND = "start" ]; then
    start $ENV
elif [ $COMMAND = "stop" ]; then
    stop $NAME
else
    exit 1
fi