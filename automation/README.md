Quickstart: running integration tests locally on Mac/Docker 

## Running in docker

See [firecloud-automated-testing](https://github.com/broadinstitute/firecloud-automated-testing).


## Running directly (with real chrome)

### Set Up

#### Render configs

##### Against dev fiab Sam
```bash
./render-local-env.sh
```

##### Against local UI pointing to dev fiab Sam
```bash
LOCAL_UI=true ./render-local-env.sh
```

##### Against local Sam pointing to the dev live env
Run `./render-local-env.sh` and then update `samApiUrl` in application.conf to:
```
  samApiUrl = "https://local.broadinstitute.org:50443/"
```

### Run tests
All test code lives in `automation/src/test/scala`.

#### From IntelliJ
It is recommended to `Open...` IntelliJ to the `automation` folder directly and import from SBT.

First, you need to set some default VM parameters for ScalaTest run configurations. In IntelliJ, go to `Run` > `Edit Configurations...`, select `ScalaTest` under `Templates`, and add these VM parameters:

```
-Djsse.enableSNIExtension=false
```

Also make sure that there is a `Build` task configured to run before launch.

Now, simply open the test spec, right-click on the class name or a specific test string, and select `Run` or `Debug` as needed. If you don't see that option, you may need to right-click the `automation/src` folder, select `Mark Directory As -> Test Sources Root`.  

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

