#!/bin/bash

SERVICE=$1

# validate mysql
echo "sleeping for 5 seconds during postgres boot..."
sleep 5
export PGPASSWORD=${SERVICE}-test
psql --username ${SERVICE}-test --no-password --host=postgres --port=5432 -d testdb -c "SELECT VERSION();SELECT NOW()"
