#!/bin/bash

# Single source of truth for building Sam.
# @ Jackie Roberti
# @ Doug Voet
#
# Provide command line options to do one or several things:
#   jar : build sam jar
#   -d | --docker : provide arg either "build" or "push", to build and push docker image
# Jenkins build job should run with all options, for example,
#   ./docker/build.sh jar publish -d push

set -ex
PROJECT=sam


function make_jar()
{
    echo "building jar..."
    OPENDJ=$(bash ./docker/run-opendj.sh start jenkins | tail -n1)
    echo $OPENDJ
    
    # Get the last commit hash of the model directory and set it as an environment variable
    GIT_MODEL_HASH=$(git log -n 1 --pretty=format:%h)
    
    # make jar.  cache sbt dependencies.
    docker run --rm --link $OPENDJ:opendj -e DIRECTORY_URL=$DIRECTORY_URL -e GIT_MODEL_HASH=$GIT_MODEL_HASH -e DIRECTORY_PASSWORD=$DIRECTORY_PASSWORD -v $PWD:/working -v jar-cache:/root/.ivy -v jar-cache:/root/.ivy2 broadinstitute/scala-baseimage /working/docker/install.sh /working
    EXIT_CODE=$?

    bash ./docker/run-opendj.sh stop jenkins $OPENDJ

    if [ $EXIT_CODE != 0 ]; then
        echo "Tests/jar build exited with status $EXIT_CODE"
        exit $EXIT_CODE
    fi
}

function docker_cmd()
{
    if [ $DOCKER_CMD = "build" ] || [ $DOCKER_CMD = "push" ]; then
        echo "building sam docker image..."
        if [ "$ENV" != "dev" ] && [ "$ENV" != "alpha" ] && [ "$ENV" != "staging" ] && [ "$ENV" != "perf" ]; then
            DOCKER_TAG=${BRANCH}
            DOCKER_TAG_TESTS=${BRANCH}
        else
            GIT_SHA=$(git rev-parse origin/${BRANCH})
            echo GIT_SHA=$GIT_SHA > env.properties
            DOCKER_TAG=${GIT_SHA:0:12}
            DOCKER_TAG_TESTS=${GIT_SHA:0:12}
        fi
        docker build -t $REPO:${DOCKER_TAG} .
        cd automation
        docker build -f Dockerfile-tests -t $TESTS_REPO:${DOCKER_TAG_TESTS} .
        cd ..

        if [ $DOCKER_CMD = "push" ]; then
            echo "pushing docker image..."
            docker push $REPO:${DOCKER_TAG}
            docker push $TESTS_REPO:${DOCKER_TAG_TESTS}
        fi
    else
        echo "Not a valid docker option!  Choose either build or push (which includes build)"
    fi
}


# parse command line options
DOCKER_CMD=
BRANCH=${BRANCH:-$(git rev-parse --abbrev-ref HEAD)}  # default to current branch
REPO=${REPO:-broadinstitute/$PROJECT}  # default to sam docker repo
TESTS_REPO=$REPO-tests
ENV=${ENV:-""}  # if env is not set, push an image with branch name

while [ "$1" != "" ]; do
    case $1 in
        jar) make_jar ;;
        -d | --docker) shift
                       echo $1
                       DOCKER_CMD=$1
                       docker_cmd
                       ;;
    esac
    shift
done
