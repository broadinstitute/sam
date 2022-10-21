# Independent Tests
A goal for Sam is to have its tests no longer rely on external services to run. Getting rid of OpenDJ is a great start,
but we still rely on a PostgresSQL database to test a lot of business logic. We're going to migrate these tests to use
mocked responses over time, but while that happens, there is an interim solution!

## Tests Without a Database
Don't want to spin up a database to run those pesky unit tests? Well, now you can (kinda)!

By setting the JVM variable `-Dpostgres.enabled=false`, Sam's tests will skip any test that tries to talk to the database.
This just-about halves the number of tests being run, so don't rely on it to test major changes in Sam.
`-Dpostgres.host` and `-Dpostgres.port` still need to be set, so that the config can render properly.
However, you can just use any value you want to fill them!

There are currently 580 tests that pass without a database.

## Test Suites To Migrate
Currently, the following test suites fail when running without a database:
```
UserRoutesV1Spec
GoogleExtensionSpec
RouteSecuritySpec
ManagedGroupRoutesV1Spec
AzureRoutesSpec
ManagedGroupRoutesSpec
UserRoutesV2Spec
DistributedLockSpec
ResourceRoutesSpec
GoogleExtensionRoutesSpec
StatusRouteSpec
AdminResourcesRoutesSpec
TermsOfServiceRouteSpec
UserRoutesSpec
AdminResourceTypesRoutesSpec
AdminUserRoutesSpec
GoogleExtensionRoutesV1Spec
ResourceRoutesV1Spec
```

As we convert tests to not need a running database, we should update this list.