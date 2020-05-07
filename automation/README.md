Quickstart: running integration tests locally on Mac/Docker 

## Running in docker

See [firecloud-automated-testing](https://github.com/broadinstitute/firecloud-automated-testing).


## Running directly (with real chrome)

### Set Up

#### Render configs

##### Against a fiab Sam
```bash
./render-local-env.sh
```

##### Against local UI pointing to a fiab Sam
```bash
LOCAL_UI=true ./render-local-env.sh
```

##### Against a local Sam
Run `./render-local-env.sh` and then update the URIs in `src/test/resources/application.conf` to:
```
  baseUrl = "https://firecloud.dsde-dev.broadinstitute.org/"
  orchApiUrl = "https://firecloud-orchestration.dsde-dev.broadinstitute.org/"
  rawlsApiUrl = "https://rawls.dsde-dev.broadinstitute.org/"
  samApiUrl = "https://local.broadinstitute.org:50443/"
  thurloeApiUrl = "https://thurloe.dsde-dev.broadinstitute.org/"
  ```
Then, you may need to run `sbt clean compile test` to pick up the new config changes.


### Run tests
All test code lives in `automation/src/test/scala`.

#### From IntelliJ
To run tests from IntelliJ, it is recommended to load the project from the `automation` folder instead of the `sam` root folder.
To do this, go to `File` -> `Open...` and navigate __into__ the `automation` folder, click `Open` to accept, and then import from SBT.

You need to set some default VM parameters for ScalaTest run configurations. In IntelliJ, go to `Run` > `Edit Configurations...`, select `ScalaTest` under `🔧Templates`, and add these VM parameters:

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

