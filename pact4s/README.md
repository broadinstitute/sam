# How to use Pact in Sam

To run the `SamProviderSpec`s locally, you will need to make sure that you populate a couple of environment variables so
that your locally running tests know how to authenticate with the DSP DevOps hosted Pact Broker.

The credentials for authenticating with the Pact Broker can be found here:

```vault read secret/dsp/pact-broker/users/read-only```

On the command line, you can try the following:

```shell
export PACT_BROKER_URL="https://pact-broker.dsp-eng-tools.broadinstitute.org/"
export PACT_BROKER_USERNAME=$(vault read -field=basic_auth_read_only_username secret/dsp/pact-broker/users/read-only)
export PACT_BROKER_PASSWORD=$(vault read -field=basic_auth_read_only_password secret/dsp/pact-broker/users/read-only)
```

In IntelliJ, you can create a Run Configuration for `SamProviderSpec.scala` and save `Environment Variables` for:

* `PACT_BROKER_URL`
* `PACT_BROKER_USERNAME`
* `PACT_BROKER_USERNAME`

## References
* https://broadworkbench.atlassian.net/wiki/spaces/IRT/pages/2660368406/Getting+Started+with+Pact+Contract+Testing
* https://broadworkbench.atlassian.net/wiki/spaces/IRT/pages/2681143308/Pact+Broker+Infrastructure
