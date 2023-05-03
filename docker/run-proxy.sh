#!/usr/bin/env bash

start() {
    echo "attempting to remove old ${CONTAINER} container..."
    docker rm -f "${CONTAINER}" || echo "docker rm failed. nothing to rm."

    # start up postgres
    echo "starting up ${CONTAINER} container..."
    echo "Creating proxy..."

    docker create --name "${CONTAINER}" \
        --restart "always" \
        -p 40080:80 -p 50443:443 \
        us.gcr.io/broad-dsp-gcr-public/httpd-terra-proxy:v0.1.17

    docker cp "${SECRETS_DIR}/server.crt" sam-proxy:/etc/ssl/certs/server.crt
    docker cp "${SECRETS_DIR}/server.key" sam-proxy:/etc/ssl/private/server.key
    docker cp "${SECRETS_DIR}/oauth2.conf" sam-proxy:/etc/httpd/conf.d/oauth2.conf
    docker cp "${SECRETS_DIR}/site.conf" sam-proxy:/etc/httpd/conf.d/site.conf

    echo "Starting proxy..."
    docker start "${CONTAINER}"
}

stop() {
    echo "Stopping docker $CONTAINER container..."
    docker stop "${CONTAINER}" || echo "${CONTAINER} stop failed. container already stopped."
    docker rm -v "${CONTAINER}" || echo "${CONTAINER} rm -v failed.  container already destroyed."
}

CONTAINER=sam-proxy
COMMAND=$1
SECRETS_DIR="$(git rev-parse --show-toplevel)/src/main/resources/rendered"

if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start|restart"
    exit 1
fi

if [ "${COMMAND}" = "start" ]; then
    start
elif [ "${COMMAND}" = "stop" ]; then
    stop
elif [ "${COMMAND}" = "restart" ]; then
    stop
    start
else
    exit 1
fi

