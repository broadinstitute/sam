#!/usr/bin/env bash

###########################
# This script tries to find Google Projects that we think still exist in Rawls, but Google thinks they are pending
# deletion.  It is hypothesized that these projects that we still think exist in Rawls but are pending deletion in
# Google will cause future problems when trying to update Service Perimeters because Rawls will be trying to add a
# project to the perimeter that no longer exists in Google.
#
# The general flow is:
#   1. Get all the ACTIVE projects from Google
#   2. Get all of the DELETE_REQUESTED projects from Google
#   3. Take the output from steps 1 and 2 and load them up into a temp table in MySQL
#   4. Query that temp table and the Billing Projects table however you need to
#
# NOTE: At this time, this script is only analyzing AoU projects.  It can/should probably be updated to check ALL
# projects.  AoU was targeted first because this weird state actually breaks AoU's ability to add projects to their
# perimeter.  For non-AoU projects right now, it might be helpful information, but it's not causing any real problems.

usage () {
  echo "Usage: ca-1206.sh [-h] [-i]"
  echo "options:"
  echo "  -h     Show this Help."
  echo "  -i     Interactive mode. Creates the insert script for the temp_table and will load you into MySQL CLI"
  echo "  -s     Skip gcloud commands.  Will run faster but will use whatever old Google Project data is already here"
  echo
}

while getopts ":ihs" opt; do
  case ${opt} in
    h )
      usage
      exit 1
      ;;
    i ) # run the script interactively
      INTERACTIVE=true
      ;;
    s ) # skip steps to get latest project info from Google
      SKIP_GOOGLE=true
      ;;
    \? )
      echo "Invalid Option: -$OPTARG" 1>&2
      exit 1
      ;;
  esac
done
shift $((OPTIND -1))

# Confirm that you are logged in with your test.firecloud.org account
GCLOUD_ACCT=$(gcloud config list account --format "value(core.account)")
if [[ "$GCLOUD_ACCT" != *"test.firecloud.org" ]]; then
  echo "Your gcloud user is current: $GCLOUD_ACCT"
  echo "You must 'gcloud auth login' as your @test.firecloud.org user"
  exit 1
fi

LOCAL_WORK_DIR=$(pwd)

# File for accumulating all VALUES for SQL INSERT
VALUES_FOR_INSERT_FILE=values_for_insert.sql

#########################################
# BEGIN getting project info from Google
#########################################
TEST_DOT_FIRECLOUD_PROJECTS_RAW_FILE=test_dot_firecloud_projects_raw.txt
TEST_DOT_FIRECLOUD_PROJECTS_RAW_PATH="$LOCAL_WORK_DIR/$TEST_DOT_FIRECLOUD_PROJECTS_RAW_FILE"

if [[ $SKIP_GOOGLE != true ]] ; then
  echo "Retrieving ALL Google Projects you have access to as: $GCLOUD_ACCT"
  echo "Started at $(date), this will take several minutes..."
  gcloud projects list > "$TEST_DOT_FIRECLOUD_PROJECTS_RAW_PATH"
  echo "Done retrieving Google Projects at: $(date)"
else
  echo "Skipping retrieval of Google Projects"
fi

# Count the lines, awk to convert to int, subtract 1 for the header labels line
ALL_PROJECT_COUNT=$(( $(wc -l < "$TEST_DOT_FIRECLOUD_PROJECTS_RAW_PATH" | awk '{print $1}') - 1 ))
AOU_PROJECT_COUNT=$(grep -c "^aou" "$TEST_DOT_FIRECLOUD_PROJECTS_RAW_PATH" )
echo "TOTAL: $ALL_PROJECT_COUNT"
echo "AoU  : $AOU_PROJECT_COUNT"

AOU_ACTIVE_PROJECT_NUMBERS_FILE=aou_active_project_numbers.txt
# grep for just the AoU projects, get the last "word" of each line which is the Google Project Number
grep -e "^aou" "$TEST_DOT_FIRECLOUD_PROJECTS_RAW_PATH" | awk '{print $NF}' > $AOU_ACTIVE_PROJECT_NUMBERS_FILE
# Wrap the project number in quotes and parens to turn each line into a valid 'VALUE' for SQL INSERT
sed -r "s/(^[0-9]+$)/('\1','ACTIVE'),/g" < "$AOU_ACTIVE_PROJECT_NUMBERS_FILE" > $VALUES_FOR_INSERT_FILE

DELETE_REQUESTED="DELETE_REQUESTED"
FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_FILE=test.firecloud_projects_pending_delete.txt
FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_PATH="$LOCAL_WORK_DIR/$FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_FILE"

if [[ $SKIP_GOOGLE != true ]] ; then
  echo "Retrieving all Google Projects pending deletion (lifecycleState == '$DELETE_REQUESTED')"
  echo "Started at $(date), this will take several minutes..."
  gcloud projects list --filter="lifecycleState:$DELETE_REQUESTED" > "$FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_PATH"
  echo "Done retrieving Google Projects pending deletion at: $(date)"
else
  echo "Skipping retrieval of Google Projects"
fi

# Count the lines, awk to convert to int, subtract 1 for the header labels line
PROJECTS_PENDING_DELETE_COUNT=$(( $(wc -l < "$FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_PATH" | awk '{print $1}') - 1 ))
AOU_PROJECTS_PENDING_DELETE_COUNT=$(grep -c "^aou" "$FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_PATH")
echo "TOTAL : $PROJECTS_PENDING_DELETE_COUNT"
echo "AoU   : $AOU_PROJECTS_PENDING_DELETE_COUNT"

AOU_PROJECT_NUMBERS_PENDING_DELETE_FILE=aou_project_numbers_pending_delete.txt
# grep for just the AoU projects, get the last "word" of each line which is the Google Project Number
grep -e "^aou" "$FIRECLOUD_PROJECTS_PENDING_DELETE_RAW_PATH" | awk '{print $NF}' > $AOU_PROJECT_NUMBERS_PENDING_DELETE_FILE
# Wrap the project number in quotes and parens to turn each line into a valid 'VALUE' for SQL INSERT
sed -r "s/(^[0-9]+$)/('\1','$DELETE_REQUESTED'),/g" < $AOU_PROJECT_NUMBERS_PENDING_DELETE_FILE >> $VALUES_FOR_INSERT_FILE

#########################################
# END getting project info from Google
#########################################

TEMP_TABLE_NAME="PROJECTS_ON_GOOGLE"
INSERT_STATEMENT_FILE=insert_statement.sql
# Replace the last comma with a semicolon
echo "INSERT INTO $TEMP_TABLE_NAME VALUES " > $INSERT_STATEMENT_FILE
sed '$s/,$/;/' < $VALUES_FOR_INSERT_FILE >> $INSERT_STATEMENT_FILE

TEMP_TABLE_SCRIPT="create_and_insert.sql"
FULL_PATH_TO_SQL="$LOCAL_WORK_DIR/$TEMP_TABLE_SCRIPT"

cat > "$FULL_PATH_TO_SQL" <<- EOM
CREATE TEMPORARY TABLE $TEMP_TABLE_NAME (
  GOOGLE_PROJECT_NUMBER varchar(255) PRIMARY KEY,
  LIFECYCLE_STATE varchar(255) NOT NULL
);
EOM

cat $INSERT_STATEMENT_FILE >> "$FULL_PATH_TO_SQL"

#### Now is your chance to add some follow-on SQL that you want to perform on the temp table

# The following query will list all the existing Billing Projects in Rawls Dev where the underlying Google Project is
# pending deletion on Google.  If the Google Project is in a state of DELETE_REQUESTED on Google, then its Billing
# Project should have already been deleted from the Rawls database, so if it still exists in our db, there is probably
# something to investigate on that Billing Project
cat >> "$FULL_PATH_TO_SQL" <<- EOM
SELECT bp.NAME, bp.GOOGLE_PROJECT_NUMBER
FROM BILLING_PROJECT bp JOIN $TEMP_TABLE_NAME gp ON bp.GOOGLE_PROJECT_NUMBER = gp.GOOGLE_PROJECT_NUMBER
WHERE gp.LIFECYCLE_STATE = '$DELETE_REQUESTED';
EOM

WORK_DIR_ON_IMAGE="/working"
#docker run -it --rm -v "$HOME":/root -v "$LOCAL_WORK_DIR":"$WORK_DIR_ON_IMAGE" broadinstitute/dsde-toolbox:dev mysql-connect.sh -o table -p firecloud -a rawls -e dev -f "$TEMP_TABLE_SCRIPT"

DOCKER_MYSQL_CONNECT_CMD="docker run -it --rm -v $HOME:/root -v $LOCAL_WORK_DIR:$WORK_DIR_ON_IMAGE broadinstitute/dsde-toolbox:dev mysql-connect.sh -o table -p firecloud -a rawls -e dev"

if [[ $INTERACTIVE = true ]] ; then
  echo
  echo "To create and populate the '$TEMP_TABLE_NAME' temp table after MySQL CLI loads, run the command: source $TEMP_TABLE_SCRIPT"
  eval "$DOCKER_MYSQL_CONNECT_CMD"
else
  eval "$DOCKER_MYSQL_CONNECT_CMD -f $TEMP_TABLE_SCRIPT"
  echo "If no SQL results were printed above, then that means this script did not discover any anomalous Billing Projects.  If results were printed, you should investigate why those Billing Projects still exist in our database even though their underlying Google Project is pending deletion."
fi
