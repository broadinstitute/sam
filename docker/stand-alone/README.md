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
1. In `docker/stand-alone/.env`, set the values for `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_CLOUD_PROJECT_DOMAIN`  

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

1.  `cd` to the `docker/stand-alone` directory and run:
    
    ```docker-compose up```
1. It will take a few minutes to complete the startup process.  When complete, SAM should be accessible at: 
[https://localhost:29443/](https://localhost:29443/)