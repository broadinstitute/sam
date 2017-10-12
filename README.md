# SAM
![](https://static1.squarespace.com/static/52f51a96e4b0ec7646cd474a/5328b57de4b067106916ef7f/56b3b2167da24f50175975bc/1504623030943/geh502.jpg?format=500w)

## To build 
Make sure git secrets is installed:
```$xslt
brew install git-secrets
```
Ensure git-secrets is run:
<i>If you use the rsync script to run locally you can skip this step</i>
```$xslt
cp -r hooks/ .git/hooks/
chmod 755 .git/hooks/apply-git-secrets.sh
```
Build jar:
```
./docker/build.sh jar
```

Build jar and docker image:
```
./docker/build.sh jar -d build
```

## To run unit tests
Spin up a local OpenDJ:
```
sh docker/run-opendj.sh
```

Make sure your `SBT_OPTS` are set:
```
export SBT_OPTS="-Ddirectory.url=ldap://localhost:3389 -Ddirectory.password=testtesttest"
```

Run tests:
```
sbt test
```
