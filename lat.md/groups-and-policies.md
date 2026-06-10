# SAM Lore and Techniques

## Deleting a Managed Group That Is a Member of Another Group

### Background: How SAM Group/Policy Relationships Work

Every resource in SAM has one or more **policies** (e.g. `owner`, `writer`, `member`, `admin`). Each policy is backed by a `SAM_GROUP` row whose name follows the convention:

```
{resource-type}_{resource-id}_{policy-name}
```

For example, the `writer` policy on a `dockstore-tool` resource named `foo` has a backing group named `dockstore-tool_foo_writer`.

When a managed group is added as a member of a resource's policy, a row is inserted into `SAM_GROUP_MEMBER` linking the managed group to the policy's backing group. This is what causes the 409 when trying to delete the managed group.

Managed groups themselves are also resources of type `managed-group`, with `admin` and `member` policies. Their backing groups are named `managed-group_{group-name}_admin` and `managed-group_{group-name}_member`.

### Why Deletion Fails With 409

`PostgresGroupDAO.deleteGroup` ([source](https://github.com/broadinstitute/sam/blob/842a2fafc8e5ac5039a1237d5a36f94b8d68dab6/src/main/scala/org/broadinstitute/dsde/workbench/sam/dataAccess/PostgresGroupDAO.scala#L258-L277)) does a SQL `DELETE` on `SAM_GROUP` and catches any `PSQLException` with a FK violation state, converting it to a 409 Conflict:

> "group X cannot be deleted because it is a member of at least 1 other group"

**The error message is misleading.** It says "member of at least 1 other group" but the underlying cause is any FK violation on the `SAM_GROUP` row — not necessarily a group membership. Check all tables that reference `SAM_GROUP` (e.g. `SAM_GROUP_MEMBER`, `SAM_RESOURCE_POLICY`, `SAM_RESOURCE_AUTH_DOMAIN`) for any references to the group before concluding it is only a membership issue.

This is intentional — SAM won't silently orphan references to a group.

### Step 1: Find Where the Group Is Used

**Direct parent groups/policies:**
```sql
SELECT rt.name AS resource_type, r.name AS resource_id, p.name AS policy_name, g.email AS child_group_email
FROM SAM_GROUP g
JOIN SAM_GROUP_MEMBER gm      ON gm.member_group_id = g.id
JOIN SAM_RESOURCE_POLICY p    ON p.group_id = gm.group_id
JOIN SAM_RESOURCE r           ON r.id = p.resource_id
JOIN SAM_RESOURCE_TYPE rt     ON rt.id = r.resource_type_id
WHERE g.name = '<child-group-name>';
```

**Auth domains:**
```sql
SELECT rt.name AS resource_type, r.name AS resource_name
FROM SAM_GROUP g
JOIN SAM_RESOURCE_AUTH_DOMAIN ad ON ad.group_id = g.id
JOIN SAM_RESOURCE r ON r.id = ad.resource_id
JOIN SAM_RESOURCE_TYPE rt ON rt.id = r.resource_type_id
WHERE g.name = '<child-group-name>';
```

Note: the "parent group" blocking deletion is usually not a standalone managed group — it is the backing group for some resource's policy. Do not delete it. Just remove the child group from it.

Note: querying `SAM_GROUP_MEMBER_FLAT` (transitive) will show the child group's own policy backing groups as additional members of the parent — these are expected and not direct memberships.

### Step 2: Remove the Child Group From Its Parent Policy

Use the admin API:
```
DELETE /api/admin/v1/resources/{resource-type}/{resource-id}/policies/{policy-name}/memberEmails/{child-group-email}
```

**Permission required:** The caller needs the `admin_remove_member` action on `resource_type_admin/{resource-type}`. This is granted by the `admin` role on that resource.

### Step 3: Check/Fix Permissions on resource_type_admin

The 404 "Resource resource_type_admin/X not found" error does **not** necessarily mean the resource doesn't exist — it may also mean you have zero permissions on it. SAM returns 404 instead of 403 as a security measure (`SecurityDirectives.scala`).

**Check who has access:**
```
GET /api/admin/v1/resourceTypes/{resource-type}/policies
```

Or in the DB:
```sql
SELECT u.email, p.name AS policy_name
FROM SAM_RESOURCE r
JOIN SAM_RESOURCE_TYPE rt ON rt.id = r.resource_type_id
JOIN SAM_RESOURCE_POLICY p ON p.resource_id = r.id
JOIN SAM_GROUP_MEMBER_FLAT f ON f.group_id = p.group_id
JOIN SAM_USER u ON u.id = f.member_user_id
WHERE rt.name = 'resource_type_admin'
AND r.name = '{resource-type}';
```

**Bootstrap: create the admin policy if it doesn't exist.**
Resource types registered by external apps (e.g. Dockstore) may have only an `owner` policy with no members. You need to create an `admin` policy. This requires being a **SAM super admin**.

```
PUT /api/admin/v1/resourceTypes/{resource-type}/policies/admin
```
```json
{
  "memberEmails": ["your-email@firecloud.org"],
  "roles": ["admin"],
  "actions": []
}
```

The `admin` role on `resource_type_admin` grants: `admin_read_policies`, `admin_add_member`, `admin_remove_member`.

This call is an **overwrite** — include any existing members if the policy already has them.

### Key Tables

| Table | Purpose |
|---|---|
| `SAM_GROUP` | All groups (managed groups and policy backing groups) |
| `SAM_GROUP_MEMBER` | Direct membership (`group_id`, `member_group_id`, `member_user_id`) |
| `SAM_GROUP_MEMBER_FLAT` | Transitive/flattened membership |
| `SAM_RESOURCE` | Resources (managed groups, workspaces, dockstore tools, etc.) |
| `SAM_RESOURCE_TYPE` | Resource type definitions |
| `SAM_RESOURCE_POLICY` | Policies — links resource + policy name to a backing SAM_GROUP |
| `SAM_RESOURCE_AUTH_DOMAIN` | Auth domain constraints on resources |
| `SAM_USER` | Users with email |
