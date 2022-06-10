#!/bin/bash

SERVICE=$1

# validate mysql
echo "sleeping for 10 seconds during postgres boot..."
sleep 10
export PGPASSWORD=${SERVICE}-test
psql --username ${SERVICE}-test --no-password --host=postgres --port=5432 -d testdb -c "SELECT VERSION();SELECT NOW()"
