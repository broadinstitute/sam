This script is for Travis to call when publishing artifacts. If you want to do the same - though you shouldn't have to:

1. Put `ARTIFACTORY_USERNAME` and `ARTIFACTORY_PASSWORD` in your env. If you don't know those values, ask around.
2. Call `sbt +publish -Dproject.isSnapshot=xxx`, where `xxx` is `true` if you want a developer-focused `-SNAP` release or `false` if you want an "official" release.

You can view what is in the artifactory here: https://broadinstitute.jfrog.io/broadinstitute/webapp/#/artifacts/browse/tree/General/libs-release-local/org/broadinstitute/dsde/workbench
