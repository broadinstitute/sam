#!/usr/bin/env bash

echo "Sleeping for $SLEEP seconds before sam startup."
sleep $SLEEP
echo "Finished sleep, starting."
java $JAVA_OPTS -jar $(find /sam -name 'sam*.jar')
