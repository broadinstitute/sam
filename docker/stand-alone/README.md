# SAM Stand-Alone

## Setup

The instructions below will configure SAM to run on your localhost at `localhost:29443`.  

If you want to run SAM on another domain, you will need to update your OAuth Web Application registration, your 
`etc/hosts` file, and your SSL Certificate.

### Docker

To run the application you will need to download and [install Docker](https://www.docker.com/get-docker).

### Google Cloud Platform

This application uses [Google OAuth](https://developers.google.com/identity/protocols/OAuth2) to authenticate users.  In
order to configure OAuth in SAM, you will need to:

1. Have or create a [Google Cloud Project](https://cloud.google.com/resource-manager/docs/cloud-platform-resource-hierarchy#projects).
1. Generate a web application [OAuth Client ID](https://developers.google.com/identity/protocols/OAuth2WebServer#creatingcred) for your
 Google Project.  
    1. Add an "Authorized JavaScript origin" - `https://localhost:29443`
    1. Add an "Authorized redirect URI" - `https://localhost:29443/o2c.html`
1. In `docker/stand-alone/.env`, set the values for `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_CLOUD_PROJECT_ID`  

### SSL Certificate

You should obtain a signed certificate from a trusted certificate authority (CA).  For testing, you may
also use a self-signed certificate.  

Whether you are using a self-signed certificate or one obtain from a trusted CA, you need to place three files in the 
`docker/stand-alone` directory.  

- Certificate file - `docker/stand-alone/server.crt`
- Certificate key - `docker/stand-alone/server.key`
- Certificate chain - `docker/stand-alone/ca-bundle.crt`

**NOTE** - The names of your certificate, key, and chain files must match those provided above because they will copied 
to the Docker HTTP proxy container during the `docker-compose` step.  

If you have `openssl` installed, you can generate a self-signed certificate using the following command:

```openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes```

You may then copy the generated `server.crt` file to `ca-bundle.crt` to use your certificate as the certificate chain.

### Starting the application

1. `cd` to the `docker/stand-alone` directory and run:
    
    ```docker-compose up```
    
1. It will take a few minutes to complete the startup process.  
1. After SAM has started, restart the `sam-opendj` container by running:

    ```docker-compose restart sam-opendj```
    
1. When complete, SAM should be accessible at: [https://localhost:29443/](https://localhost:29443/)

#### Restarting the application

Data in your containers should be persisted when stopping and restarting them so long as you do not run a command that 
destroys, removes, or recreates any of the SAM containers.  

If you would like to do a clean restart that destroys all previously entered data in your Docker containers and 
recreates them, use this command: 

```docker-compose up --force-recreate```

After SAM has restarted, restart the `sam-opendj` container by running:

```docker-compose restart sam-opendj```

## Using SAM

1. Open the SAM Swagger UI at: [https://localhost:29443/](https://localhost:29443/)
1. Click the `Authorize` button in the upper right of the page to log into SAM
    1. In the window that pops up, check the 3 checkboxes (`openid`, `email`, and `profile`)
    1. Click the `Authorize` button at the bottom of the pop-up window
1. Register your user account in the **Users** section by expanding the `POST /register/user` operation and clicking the
`Try it out!` button
1. Confirm you are registered by expanding the GET `/register/user` operation and clicking the `Try it out!` button.  
The response code should be `200` and the the `Response Body` should include:

    ```
    "enabled": {
        "ldap": true,
        "allUsersGroup": true,
        "google": true
      }
    ```
1. You should now be able to use the SAM APIs as desired

## Building SAM

If you would like to make code changes to SAM and test those changes, do the following:

1. `cd` to the SAM root directory
1. Run `sbt assembly` - This should build a `.jar` in `target/scala-2.12`
1. Copy the `.jar` to SAM's root directory: `cp target/scala-2.12/sam-assembly-0.1-xxxxxxx-SNAPSHOT.jar .`
1. [Re-build](https://docs.docker.com/engine/reference/commandline/build/) the `sam-app` Docker container by running 
`docker build -t sam/my-tag .`  You can name the tag whatever you want by changing the argument passed to `-t`. 
1. Update `.env` and set `SAM_APP_IMAGE=sam/my-tag` (or whatever name you chose for your tag)
