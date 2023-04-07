#!/usr/bin/env bash

start() {
    echo "attempting to remove old ${CONTAINER} container..."
    docker rm -f "${CONTAINER}" || echo "docker rm failed. nothing to rm."

    if [[ ! -d "${CONFIG_DIR}" ]]; then
      echo "Could not find firecloud-develop rendered configs. Exiting!"
      exit 1
    fi

    # start up postgres
    echo "starting up ${CONTAINER} container..."
    echo "Creating proxy..."

    docker create --name "${CONTAINER}" \
        --restart "always" \
        -p 40080:80 -p 50443:443 \
        -e PROXY_URL='http://host.docker.internal:8080/' \
        -e PROXY_URL2='http://host.docker.internal:8080/api' \
        -e PROXY_URL3='http://host.docker.internal:8080/register' \
        -e LIVENESS_PROXY_URL='http://host.docker.internal:9000/liveness' \
        -e LIVENESS_PROXY_PATH='/liveness' \
        -e CALLBACK_URI='https://local.broadinstitute.org/oauth2callback' \
        -e LOG_LEVEL='debug' \
        -e SERVER_NAME='local.broadinstitute.org' \
        -e APACHE_HTTPD_TIMEOUT='650' \
        -e APACHE_HTTPD_KEEPALIVE='On' \
        -e APACHE_HTTPD_KEEPALIVETIMEOUT='650' \
        -e APACHE_HTTPD_MAXKEEPALIVEREQUESTS='500' \
        -e APACHE_HTTPD_PROXYTIMEOUT='650' \
        -e PROXY_TIMEOUT='650' \
        -e REMOTE_USER_CLAIM='sub' \
        -e ENABLE_STACKDRIVER='yes' \
        -e FILTER2='AddOutputFilterByType DEFLATE application/json text/plain text/html application/javascript application/x-javascript' \
        us.gcr.io/broad-dsp-gcr-public/httpd-terra-proxy:v0.1.16

    docker cp "${CONFIG_DIR}/server.crt" sam-proxy:/etc/ssl/certs/server.crt
    docker cp "${CONFIG_DIR}/server.key" sam-proxy:/etc/ssl/private/server.key
    docker cp "${CONFIG_DIR}/ca-bundle.crt" sam-proxy:/etc/ssl/certs/server-ca-bundle.crt
    docker cp "${CONFIG_DIR}/oauth2.conf" sam-proxy:/etc/httpd/conf.d/oauth2.conf
    docker cp "${CONFIG_DIR}/site.conf" sam-proxy:/etc/httpd/conf.d/site.conf

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
CONFIG_DIR="$(git rev-parse --show-toplevel)/config"

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

