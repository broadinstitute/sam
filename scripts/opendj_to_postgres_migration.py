import ldap
import subprocess
import psycopg2
from datetime import datetime
import re


####
env = 'dev'
postgresHost = "localhost"
postgresPort = 5432
user = "sam-test"
password = "sam-test"
database = "testdb"
ldapHost = "ldaps://opendj.dsde-{env}.broadinstitute.org".format(env=env)


####


# --------------------------------------------- LDAP HELPERS ---------------------------------------------#

def open_ldap_connection():
    log(str(datetime.now()) + " INITIALIZING LDAP CONNECTION")
    l = ldap.initialize(ldapHost)
    cmd = 'docker run -v $HOME:/root --rm broadinstitute/dsde-toolbox vault read -field=ldap_password "secret/dsde/firecloud/{env}/sam/sam.conf"'.format(
        env=env)
    p = subprocess.check_output(cmd, shell=True)
    q = p.decode('unicode-escape').encode('ascii').strip()
    l.simple_bind_s("cn=Directory Manager", q)
    return l


def close_ldap_connection(ldap_connection):
    ldap_connection.unbind()


def ldap_search(ldap_connection, search_filter, base_dns="dc=dsde-{env},dc=broadinstitute,dc=org"):
    base_dns_formatted = base_dns.format(env=env)
    search_scope = ldap.SCOPE_SUBTREE
    retrieve_attributes = None

    ldap_result_id = ldap_connection.search(base_dns_formatted, search_scope, search_filter, retrieve_attributes)
    result_type, result_data = ldap_connection.result(ldap_result_id)
    return result_data


def get_enabled_members(ldapConnection):
    enabled_user_dns = "cn=enabled-users,ou=groups,dc=dsde-{env},dc=broadinstitute,dc=org".format(env=env)
    enabled_search_filter = "(objectClass=*)"
    enabled_search_scope = ldap.SCOPE_SUBTREE
    enabled_retrieve_attributes = None

    enabled_users_id = ldapConnection.search(enabled_user_dns, enabled_search_scope, enabled_search_filter,enabled_retrieve_attributes)
    result_type_enabled, result_data_enabled = ldapConnection.result(enabled_users_id, 0)
    return result_data_enabled[0][1]["member"]


# ------------------------------------------- POSTGRES HELPERS -------------------------------------------#

def open_postgres_connection():
    connection = psycopg2.connect(host=postgresHost, database=database, user=user, password=password, port=postgresPort)
    connection.autocommit = True
    return connection


def database_call(sql_query, ldap_entity, cursor, return_result=False):
    try:
        cursor.execute(sql_query)
        if return_result:
            result = cursor.fetchone()[0]
            return result
        return True
    except (psycopg2.errors.DatabaseError,
            psycopg2.errors.IntegrityError,
            psycopg2.errors.UniqueViolation,
            psycopg2.errors.InFailedSqlTransaction,
            psycopg2.errors.ForeignKeyViolation,
            psycopg2.errors.SyntaxError,
            psycopg2.errors.NotNullViolation,
            psycopg2.errors.InvalidTextRepresentation,
            TypeError) as err:
        log(str(ldap_entity) + " " + str(err))
        return False


def close_postgres_connection(connection):
    connection.close()


def to_time_stamp(time_as_string):
    for fmt in ("%Y%m%d%H%M%S.%fZ", "%Y%m%d%H%M%S.%f%z"):
        try:
            return datetime.strptime(time_as_string, fmt)
        except ValueError:
            pass
    return "WHAT"  # TODO - raise exception


def get_attribute(attributes, key):
    # try:
    return attributes[key][0].decode('ascii')
# except KeyError:
#    log(str(attributes) + " does not have key ", key)


def get_attribute_array(attributes, key):
    att = attributes[key]
    return list(map(lambda x: x.decode('ascii'), att))

# ---------------------------------------------- LOGGING ---------------------------------------------#

migration_log = open("migration_log.txt", "a")


def log(log_string):
    migration_log.write(log_string + "\n")


def close_log():
    migration_log.close()

# ---------------------------------------- GET DATA FROM LDAP ----------------------------------------#

ENABLED_MEMBERS = []
ALL_PERSONS = []
ALL_PET_SERVICE_ACCOUNTS = []
ALL_RESOURCES = []
ALL_GROUPS = []
ALL_POLICY_GROUPS = []


def populate_entities():
    global ENABLED_MEMBERS
    global ALL_PERSONS
    global ALL_PET_SERVICE_ACCOUNTS
    global ALL_RESOURCES
    global ALL_GROUPS
    global ALL_POLICY_GROUPS
    ldap_connection = open_ldap_connection()
    ENABLED_MEMBERS = get_enabled_members(ldap_connection) #ldap_search(ldap_connection, "(objectClass=*)", base_dns="cn=enabled-users,ou=groups,dc=dsde-{env},dc=broadinstitute,dc=org")
    ALL_PERSONS = ldap_search(ldap_connection, "(objectClass=workbenchPerson)")
    ALL_PET_SERVICE_ACCOUNTS = ldap_search(ldap_connection, "(objectClass=petServiceAccount)")
    ALL_RESOURCES = ldap_search(ldap_connection, "(objectClass=resource)")
    ALL_GROUPS = ldap_search(ldap_connection, "(objectClass=workbenchGroup)")
    ALL_POLICY_GROUPS = ldap_search(ldap_connection, "(&(objectClass=workbenchGroup)(objectClass=policy))")
    close_ldap_connection(ldap_connection)

# ---------------------------------------- PREPARED STATEMENTS ----------------------------------------#

prepare_sam_user_name = "sam_user_plan"
prepare_sam_user_query = """prepare {name} as
                            INSERT INTO SAM_USER (id,
                                                  email,
                                                  google_subject_id,
                                                  enabled)
                            VALUES ($1,
                                    $2,
                                    $3,
                                    $4);""".format(name=prepare_sam_user_name)

prepare_pet_service_account_name = "pet_service_account_plan"
prepare_pet_service_account_query = """prepare {name} as
                                       INSERT INTO SAM_PET_SERVICE_ACCOUNT (user_id,
                                                                            project,
                                                                            google_subject_id,
                                                                            email,
                                                                            display_name)
                                              VALUES ($1,
                                                      $2,
                                                      $3,
                                                      $4,
                                                      $5);""".format(name=prepare_pet_service_account_name)

prepare_group_name = "group_plan"
prepare_group_query = """prepare {name} as
                         INSERT INTO SAM_GROUP (name,
                                                email,
                                                updated_date,
                                                synchronized_date)
                                VALUES ($1,
                                        $2,
                                        $3,
                                        $4)
                        RETURNING id;""".format(name=prepare_group_name)

prepare_group_member_name = "group_member_people_plan"
prepare_group_member_people_query = """prepare group_member_people_plan as
                                       INSERT INTO SAM_GROUP_MEMBER (group_id,
                                                                     member_user_id,
                                                                     member_group_id)
                                       VALUES ((SELECT id FROM SAM_GROUP WHERE email = $1),
                                               $2,
                                               null);""".format(name=prepare_group_member_name)
prepare_group_member_group_query = """prepare group_member_group_plan as
                                      INSERT INTO SAM_GROUP_MEMBER (group_id,
                                                                     member_user_id,
                                                                     member_group_id)
                                      VALUES ((SELECT id FROM SAM_GROUP WHERE email = $1),
                                              null,
                                              (SELECT id FROM SAM_GROUP WHERE name = $2));""".format(name=prepare_group_member_name)  # TODO Name or email

prepare_access_instructions_name = "access_instructions_plan"
prepare_access_instructions_query = """prepare {name} as
                                       INSERT INTO SAM_ACCESS_INSTRUCTIONS (group_id,
                                                                        instructions)
                                              VALUES ($1,
                                                      $2);""".format(name=prepare_access_instructions_name)

prepare_resource_name = "resource_plan"
prepare_resource_query = """prepare {name} as
                            INSERT INTO SAM_RESOURCE (name,
                                                      resource_type_id)
                                   VALUES ($1,
                                           (SELECT id FROM SAM_RESOURCE_TYPE rt
                                                      WHERE rt.name = $2))
                            RETURNING id;""".format(name=prepare_resource_name)

prepare_auth_domain_name = "auth_domain_plan"
prepare_auth_domain_query = """prepare {name} as
                               INSERT INTO SAM_RESOURCE_AUTH_DOMAIN (resource_id,
                                                                     group_id)
                                      VALUES ($1,
                                              (SELECT id FROM SAM_GROUP
                                                         WHERE name = $2));""".format(name=prepare_auth_domain_name)

prepare_policy_name = "policy_plan"
prepare_policy_query = """prepare {name} as
                           INSERT INTO SAM_RESOURCE_POLICY (resource_id,
                                                            group_id,
                                                            name)
                                  VALUES ((SELECT id FROM SAM_RESOURCE
                                                     WHERE name = $1
                                                     AND resource_type_id = $2),
                                          (SELECT id FROM SAM_GROUP
                                                     WHERE name = $3),
                                          $3)
                           RETURNING id;""".format(name=prepare_policy_name)

prepare_resource_type_name = "resource_type_plan"
prepare_resource_type_query = """prepare {name} as SELECT id FROM SAM_RESOURCE_TYPE WHERE name = $1;""".format(name=prepare_resource_type_name)

prepare_policy_role_name = "policy_role"
prepare_policy_role_query = """prepare {name} as
                               INSERT INTO SAM_POLICY_ROLE (resource_policy_id,
                                                            resource_role_id)
                                      VALUES ($1,
                                              (SELECT id FROM SAM_RESOURCE_ROLE
                                                         WHERE role = $2
                                                         AND resource_type_id = $3));""".format(name=prepare_policy_role_name)

prepare_policy_action_name = "policy_action"
prepare_policy_action_query = """prepare {name} as
                                 INSERT INTO SAM_POLICY_ACTION (resource_policy_id,
                                                                resource_action_id)
                                        VALUES ($1,
                                                (SELECT id FROM SAM_RESOURCE_ACTION
                                                           WHERE action = $2
                                                           AND resource_type_id = $3));""".format(name=prepare_policy_action_name)


# ---------------------------------------- USER MIGRATION ----------------------------------------#


def migrate_sam_users():
    start = datetime.now()
    log("BEGINNING MIGRATION TO SAM_USER TABLE")

    inserted = 0

    postgres_connection = open_postgres_connection()
    cursor = postgres_connection.cursor()

    database_call(prepare_sam_user_query, prepare_sam_user_query, cursor)

    for person in ALL_PERSONS:
        try:
            dn, attributes = person
            enabled = dn in ENABLED_MEMBERS
            uid = get_attribute(attributes, "uid")
            email = get_attribute(attributes, "mail")  # TO-DO: check that email is valid
            google_subject_id = get_attribute(attributes, "googleSubjectId")
            if len(google_subject_id) <= 30:
                query = """execute sam_user_plan ('{uid}', '{email}', '{google_subject_id}', {enabled})""" \
                    .format(uid=uid, email=email, google_subject_id=google_subject_id, enabled=enabled)
                user_inserted = database_call(query, person, cursor)
                if user_inserted: inserted += 1
            else:
                log("Too long: {google_subject_id}".format(google_subject_id=google_subject_id))
        except KeyError as ke:
            log(str(person) + " does not have key. Message: " + str(ke))

    close_postgres_connection(postgres_connection)
    end = datetime.now()
    length_all_persons = len(ALL_PERSONS)
    log("SAM_USER TOTAL TIME " + str(end - start))
    log("SAM_USER TOTAL " + str(length_all_persons))
    log("SAM_USER INSERTED " + str(inserted))
    log("SAM_USER MISSED " + str(length_all_persons - inserted))
    log("ENDED MIGRATION TO SAM_USER TABLE" + str(end - start))


def migrate_pet_service_accounts():
    start = datetime.now()
    log(str(start) + " BEGINNING MIGRATION TO SAM_PET_SERVICE_ACCOUNT TABLE")

    postgres_connection = open_postgres_connection()
    cursor = postgres_connection.cursor()

    database_call(prepare_pet_service_account_query, prepare_pet_service_account_query, cursor)

    inserted = 0
    for account in ALL_PET_SERVICE_ACCOUNTS:
        try:
            dn, attributes = account
            project = get_attribute(attributes, "project")
            google_subject_id = get_attribute(attributes, "googleSubjectId")
            display_name = get_attribute(attributes, "givenName")
            email = get_attribute(attributes, "mail")
            regex = r"\d{21}"
            matches = re.findall(regex, email)
            if len(matches) == 1:
                user_id = matches[0]
                query = """execute pet_service_account_plan ('{user_id}','{project}','{google_subject_id}','{email}','{display_name}')""" \
                    .format(user_id=user_id, project=project, google_subject_id=google_subject_id, email=email, display_name=display_name)
            pet_service_account_inserted = database_call(query, account, cursor)
            if pet_service_account_inserted: inserted += 0
        except KeyError as ke:
            log(str(account) + " does not have key. Message: " + str(ke))

    close_postgres_connection(postgres_connection)
    end = datetime.now()
    length_all_pet_service_accounts = len(ALL_PET_SERVICE_ACCOUNTS)
    log("SAM_PET_SERVICE_ACCOUNT TOTAL TIME" + str(end - start))
    log("SAM_PET_SERVICE_ACCOUNT TOTAL" + str(length_all_pet_service_accounts))
    log("SAM_PET_SERVICE_ACCOUNT INSERTED" + str(inserted))
    log("SAM_PET_SERVICE_ACCOUNT MISSED" + str(length_all_pet_service_accounts - inserted))


# ---------------------------------------- GROUP MIGRATION ----------------------------------------#

def migrate_groups():
    start = datetime.now()
    log(str(start) + "BEGINNING MIGRATION TO SAM_GROUP TABLE")

    groups_total_count = 0
    groups_inserted_count = 0
    old_policies_count = 0
    access_instructions_total_count = 0
    access_instructions_inserted_count = 0

    groups_migrated = []

    def get_email(group):
        dn, attributes = group
        return get_attribute(attributes, "mail")

    all_policy_emails = list(map(lambda x: get_email(x), ALL_POLICY_GROUPS))

    postgres_connection = open_postgres_connection()
    cursor = postgres_connection.cursor()

    database_call(prepare_group_query, prepare_group_query, cursor)
    database_call(prepare_access_instructions_query, prepare_access_instructions_query, cursor)

    for group in ALL_GROUPS:
        groups_total_count += 1
        try:
            dn, attributes = group
            email = get_attribute(attributes, "mail")  # TO-DO: check that email is valid
            object_classes = get_attribute_array(attributes, "objectClass")
            policy = "policy" in object_classes
            old_policy = (not policy) and (email in all_policy_emails)
            if old_policy:
                old_policies_count += 1
            else:
                if "groupUpdatedTimestamp" in attributes:
                    group_updated_timestamp = get_attribute(attributes, "groupUpdatedTimestamp")
                    updated_date_raw = to_time_stamp(group_updated_timestamp)
                    updated_date = "'{updated_date_raw}'".format(updated_date_raw=updated_date_raw)
                else:
                    updated_date = "null"
                if "groupSynchronizedTimestamp" in attributes:
                    group_synchronized_timestamp = get_attribute(attributes, "groupSynchronizedTimestamp")
                    synchronized_date_raw = to_time_stamp(group_synchronized_timestamp)
                    synchronized_date = "'{synchronized_date_raw}'".format(synchronized_date_raw=synchronized_date_raw)
                else:
                    synchronized_date = "null"

                if policy:
                    # TODO - change this to the decided upon naming schema
                    resource_id = get_attribute(attributes, "resourceId")
                    resource_type = get_attribute(attributes, "resourceType")
                    policy = get_attribute(attributes, "policy")
                    name = resource_id + resource_type + policy
                else:
                    name = get_attribute(attributes, "cn")

                group_query = """execute group_plan ('{name}', '{email}', {updated_date}, {synchronized_date})""" \
                    .format(name=name, email=email, updated_date=updated_date, synchronized_date=synchronized_date)
                group_id = database_call(group_query, group, cursor)
                if group_id: groups_inserted_count += 1
                groups_migrated.append(group)

                if "accessInstructions" in attributes:
                    access_instructions_total_count += 1
                    access_instructions = get_attribute_array(attributes, "accessInstructions")
                    group_query = """execute access_instructions_plan ('{group_id}', '{access_instructions}')""" \
                        .format(group_id=group_id, access_instructions=access_instructions)
                    access_instructions_inserted = database_call(group_query, group, cursor)
                    if access_instructions_inserted: access_instructions_inserted_count += 1

        except KeyError as ke:
            log(str(group) + " does not have key. Message: " + str(ke))

    close_postgres_connection(postgres_connection)
    end = datetime.now()
    log("SAM_GROUP TOTAL TIME " + str(end - start))
    log("SAM_GROUP TOTAL " + str(groups_total_count))
    log("SAM_GROUP INSERTED " + str(groups_inserted_count))
    log("SAM_GROUP MISSED " + str(groups_total_count - groups_inserted_count))
    log("SAM_GROUP OLD POLICIES SKIPPED " + str(old_policies_count))
    log("SAM_ACCESS_INSTRUCTIONS TOTAL " + str(access_instructions_total_count))
    log("SAM_ACCESS_INSTRUCTIONS INSERTED " + str(access_instructions_inserted_count))
    log("SAM_ACCESS_INSTRUCTIONS MISSED " + str(access_instructions_total_count - access_instructions_inserted_count))
    migrate_group_members(groups_migrated)


def migrate_group_members(groups):
    start = datetime.now()
    log(str(start) + "BEGINNING MIGRATION TO SAM_GROUP_MEMBER TABLE")

    total = 0
    people_total = 0
    people_inserted = 0
    groups_total = 0
    groups_inserted = 0
    resources_total = 0
    resources_inserted = 0
    unknown_total = 0

    postgres_connection = open_postgres_connection()
    cursor = postgres_connection.cursor()

    database_call(prepare_group_member_people_query, prepare_group_member_people_query, cursor)
    database_call(prepare_group_member_group_query, prepare_group_member_group_query, cursor)

    for group in groups:
        try:
            dn, attributes = group
            email = get_attribute(attributes, "mail")
            if "uniqueMember" in attributes:
                unique_members = get_attribute_array(attributes, "uniqueMember")
                for member in unique_members:
                    total += 1
                    if "ou=people" in member.lower():
                        people_total += 1
                        regex = r"[a-z0-9]{21}"
                        matches = re.findall(regex, member)
                        if len(matches) == 1:
                            user_member_id = matches[0]
                            query = """execute group_member_people_plan ('{email}','{user_member_id}')""" \
                                .format(email=email, user_member_id=user_member_id)
                            inserted = database_call(query, member, cursor)
                            if inserted:
                                people_inserted += 1
                        else:
                            log("Could not find uid for group member for " + str(member))
                    elif "ou=group" in member.lower():
                        groups_total += 1
                        regex = r"cn=[a-zA-Z0-9_-]*"
                        matches = re.findall(regex, member)
                        if len(matches) == 1:
                            match = matches[0]
                            group_member_id = match.split("=")[1]
                            query = """execute group_member_group_plan ('{email}','{group_member_id}')""" \
                                .format(email=email, group_member_id=group_member_id)
                            inserted = database_call(query, member, cursor)
                            if inserted:
                                groups_inserted += 1
                        else:
                            log("Could not find uid for group member for " + str(member))
                    elif "ou=resources":
                        resources_total += 1
                    else:
                        unknown_total += 1
                        log("NO TYPE??? " + str(group)) # TODO: Change this
            else:
                pass
        except KeyError as ke:
            log(str(group) + " does not have key. Message: " + str(ke))

    close_postgres_connection(postgres_connection)
    end = datetime.now()
    log("SAM_GROUP_MEMBER TOTAL TIME " + str(end - start))
    log("SAM_GROUP_MEMBER TOTAL: " + str(total))
    log("SAM_GROUP_MEMBER PEOPLE_TOTAL: " + str(people_total))
    log("SAM_GROUP_MEMBER PEOPLE INSERTED: " + str(people_inserted))
    log("SAM_GROUP_MEMBER GROUPS TOTAL: " + str(groups_total))
    log("SAM_GROUP_MEMBER GROUPS INSERTED: " + str(groups_inserted))
    log("SAM_GROUP_MEMBER RESOURCES TOTAL: " + str(resources_total))
    log("SAM_GROUP_MEMBER RESOURCES INSERTED: " + str(resources_inserted))
    log("SAM_GROUP_MEMBER UNKNOWN TOTAL: " + str(unknown_total))


# ---------------------------------------------- RESOURCE MIGRATION ----------------------------------------------#


def migrate_resources():
    start = datetime.now()
    log(str(start) + "BEGINNING MIGRATION TO SAM_RESOURCE TABLE")

    postgres_connection = open_postgres_connection()
    cursor = postgres_connection.cursor()

    database_call(prepare_resource_query, prepare_resource_query, cursor)
    database_call(prepare_auth_domain_query, prepare_auth_domain_query, cursor)

    resource_total = 0
    resource_inserted = 0
    domain_total = 0
    domain_inserted = 0


    for resource in ALL_RESOURCES:
        resource_total += 1
        try:
            dn, attributes = resource
            resource_type = get_attribute(attributes, "resourceType")
            resource_name = get_attribute(attributes, "resourceId")
            query = """execute resource_plan ('{resource_name}','{resource_type}')""" \
                .format(resource_name=resource_name, resource_type=resource_type)
            resource_id = database_call(query, resource, cursor, True)
            if resource_id: resource_inserted += 1

            if "authDomain" in attributes:
                domain_total += 1
                auth_domains = get_attribute_array(attributes, "authDomain")
                for domain in auth_domains:
                    domain_total += 1
                    auth_domain_query = """execute auth_domain_plan ('{resource_id}','{domain}')""" \
                        .format(resource_id=resource_id, domain=domain)
                    domain_id = database_call(auth_domain_query, resource, cursor)
                    if domain_id: domain_inserted += 1
        except KeyError as ke:
            log(str(resource) + " does not have key. Message: " + str(ke))

    close_postgres_connection(postgres_connection)
    end = datetime.now()
    log("SAM_RESOURCE TOTAL TIME " + str(end - start))
    log("RESOURCES MIGRATED " + str(resource_total))
    log("RESOURCE INSERTED " + str(resource_inserted))
    log("RESOURCE MISSED " + str(resource_total - resource_inserted))
    log("AUTH DOMAINS MIGRATED " + str(domain_total))
    log("AUTH DOMAINS INSERTED " + str(domain_inserted))
    log("AUTH DOMAINS MISSED " + str(domain_total - domain_inserted))


# ----------------------------------------------POLICY MIGRATION ----------------------------------------------#

def migrate_policies():
    start = datetime.now()
    log(str(start) + "BEGINNING MIGRATION TO SAM_POLICY TABLE")

    postgres_connection = open_postgres_connection()
    cursor = postgres_connection.cursor()

    database_call(prepare_policy_query, prepare_policy_query, cursor)
    database_call(prepare_policy_role_query, prepare_policy_role_query, cursor)
    database_call(prepare_policy_action_query, prepare_policy_action_query, cursor)
    database_call(prepare_resource_type_query, prepare_resource_type_query, cursor)

    policy_total = 0
    policy_inserted = 0
    policy_missed = 0
    role_total = 0
    role_inserted = 0
    role_missed = 0
    action_total = 0
    action_inserted = 0
    action_missed = 0

    for policy in ALL_POLICY_GROUPS:
        policy_total += 1
        try:
            dn, attributes = policy
            resource_id = get_attribute(attributes, "resourceId")
            resource_type = get_attribute(attributes, "resourceType")
            policy_name = get_attribute(attributes, "policy")
            group_name = resource_id + resource_type + policy_name # TO DO - change this to the decided upon naming scheme
            resource_type_id = database_call("""execute {name} ('{resource_type}');""".format(name=prepare_resource_type_name, resource_type=resource_type),policy, cursor, True)

            policy_query = """execute {name} ('{resource_id}','{resource_type_id}','{group_name}')""" \
                .format(name=prepare_policy_name, resource_id=resource_id, resource_type_id=resource_type_id, group_name=group_name)
            policy_id = database_call(policy_query, policy, cursor, True)
            if policy_id: policy_inserted += 1
            else: policy_missed += 1

            if "role" in attributes:
                roles = get_attribute_array(attributes, "role")
                for role in roles:
                    role_total += 1
                    role_query = """execute {name} ('{policy_id}','{role}','{resource_type_id}')""" \
                        .format(name=prepare_policy_role_name, policy_id=policy_id, role=role, resource_type_id=resource_type_id)
                    role_id = database_call(role_query, policy, cursor)
                    if role_id: role_inserted += 1
                    else: role_missed += 1

            if "action" in attributes:
                actions = get_attribute_array(attributes, "action")
                for action in actions:
                    action_total += 1
                    action_query = """execute {name} ('{policy_id}','{action}','{resource_type_id}')""" \
                        .format(name=prepare_policy_action_name, policy_id=policy_id, action=action, resource_type_id=resource_type_id)
                    action_id = database_call(action_query, policy, cursor)
                    if action_id: action_inserted += 1
                    else: action_missed += 1

        except KeyError as ke:
            log(str(policy) + " does not have key. " + str(ke))

    close_postgres_connection(postgres_connection)
    end = datetime.now()
    print("SAM_POLICY TOTAL TIME " + str(end - start))
    log("SAM_POLICY TOTAL TIME " + str(end - start))
    log("POLICIES MIGRATED " + str(policy_total))
    log("POLICIES INSERTED " + str(policy_inserted))
    log("POLICIES MISSED " + str(policy_missed))
    log("ROLES MIGRATED " + str(role_total))
    log("ROLES INSERTED " + str(role_inserted))
    log("ROLES MISSED " + str(role_missed))
    log("ACTIONS MIGRATED " + str(action_total))
    log("ACTIONS INSERTED " + str(action_inserted))
    log("ACTIONS MISSED " + str(action_missed))

# TO-DO: Do not migrate groups with cns that have [uuid]-[workspace access level] format
# TO-DO: Do not migrate entries with emails that are invalid (examples: workbenchPerson with a uid in the mail field)


def migrate():
    print()
    log(str(datetime.now()) + " START MIGRATION SCRIPT")
    start = datetime.now()
    populate_entities()
    migrate_sam_users()
    migrate_pet_service_accounts()
    migrate_groups()
    migrate_resources()
    migrate_policies()
    end = datetime.now()
    log("START: " + str(start))
    log("END: " + str(end))
    log("TOTAL TIME: " + str(end - start))
    print("START: " + str(start))
    print("END: " + str(end))
    print("TOTAL TIME: " + str(end - start))
    close_log()


migrate()
