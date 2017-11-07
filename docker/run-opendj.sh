#!/usr/bin/env bash

start()  {
    echo "starting up container $1..."
    docker run --name $1 -e ROOTPASS="testtesttest" -e BASE_DN=dc=dsde-dev,dc=broadinstitute,dc=org -v ${PWD}/docker/opendjsetup.sh:/opt/opendj/bootstrap/setup.sh -v ${PWD}/docker/example-v1.json:/opt/example-v1.json -d -p 389 broadinstitute/openam:opendj
    echo "sleeping 40 seconds til opendj is up and happy. This does not check anything."
    sleep 40
}

stop() {
    echo "stopping $1"
    docker stop $1 || echo "$1 stop failed. container already stopped."
    docker rm -v $1 || echo "$1 rm -v failed.  container already destroyed."

}

COMMAND=$1
if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start"
    exit 1
fi

if [ $COMMAND = "start" ]; then
    CONTAINER=opendj-$(date +"%y%m%d_%H%M%S")
    start $CONTAINER
    echo $CONTAINER
elif [ $COMMAND = "stop" ]; then
    stop $2
else
    exit 1
fi