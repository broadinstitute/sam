# Sam Smoke Tests

These smoke tests provide a means for running a small set of tests against a live running Sam instance to validate that
it is up and functional.  These tests should verify more than the `/status` endpoint and should additionally try to 
verify some basic functionality of Sam.  

These tests should run quickly (no longer than a few seconds), should be idempotent, and when possible, should not 
make any changes to the state of the service or its data.  

## Requirements

Python 3.10.3 or higher

## Setup

You will need to install required pip libraries:

```pip install -r requirements.txt```

## Run

The smoke tests have 2 different modes that they can run in: authenticated or unauthenticated.  The mode will be 
automatically selected based on the arguments you pass to `smoke_test.py`.

To run the _unauthenticated_ smoke tests:

```python smoke_test.py {SAM_HOST}```

```python smoke_test.py sam.dsde-dev.broadinstitute.org```

To run all (_authenticated_ and _unauthenticated_) smoke tests:

```python smoke_test.py {SAM_HOST} $(gcloud auth print-access-token)```

```python smoke_test.py sam.dsde-dev.broadinstitute.org $(gcloud auth print-access-token)```

## Required and Optional Arguments

### SAM_HOST
Required - Can be just a domain or a domain and port:

* `sam.dsde-dev.broadinstitute.org`
* `sam.dsde-dev.broadinstitute.org:443`

The protocol can also be added if you desire, however, most Sam instance can and should use HTTPS and this is the
default if no protocol is specified:

* `http://sam.dsde-dev.broadinstitute.org`
* `https://sam.dsde-dev.broadinstitute.org`

### USER_TOKEN
Optional - A `gcloud` access token.  If present, `smoke_test.py` will execute all unauthenticated tests as well as all
authenticated tests using the access token provided in this argument. 

### Verbosity
Optional - You may control how much information is printed to `STDOUT` while running the smoke tests by passing a 
verbosity argument to `smoke_test.py`.  For example to print more information about the tests being run:

```python -v 2 smoke_test.py {SAM_HOST}```