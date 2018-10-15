Quickstart: running integration tests locally on Mac/Docker 

## Running in docker

See [firecloud-automated-testing](https://github.com/broadinstitute/firecloud-automated-testing).


## Running directly (with real chrome)

### Set Up

Render configs:
```bash
./render-local-env.sh [branch of firecloud-automated-testing] [vault token] [env] [service root]
```

**Arguments:** (arguments are positional)

* branch of firecloud-automated-testing
    * Configs branch; defaults to `master`
* Vault auth token
	* Defaults to reading it from the .vault-token via `$(cat ~/.vault-token)`.
* env
	* Environment of your FiaB; defaults to `dev`
* service root
    * the name of your local clone of sam if not `sam`
	
### Run tests

#### From IntelliJ

First, you need to set some default VM parameters for ScalaTest run configurations. In IntelliJ, go to `Run` > `Edit Configurations...`, select `ScalaTest` under `Defaults`, and add these VM parameters:

```
-Djsse.enableSNIExtension=false
```

Also make sure that there is a `Build` task configured to run before launch.

Now, simply open the test spec, right-click on the class name or a specific test string, and select `Run` or `Debug` as needed. A good one to start with is `GoogleSpec` to make sure your base configuration is correct. All test code lives in `automation/src/test/scala`. FireCloud test suites can be found in `automation/src/test/scala/org/broadinstitute/dsde/firecloud/test`.

#### From the command line

To run all tests:

```bash
sbt test
```

To run a single suite:

```bash
sbt "testOnly *SamApiSpec"
```

To run a single test within a suite:

```bash
# matches test via substring
sbt "testOnly *SamApiSpec -- -z \"have a search field\""
```

For more information see [SBT's documentation](https://www.scala-sbt.org/1.x/docs/Testing.html#testOnly).

