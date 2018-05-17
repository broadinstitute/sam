# SAM Stand-Alone

## Setup

The instructions below will configure SAM to run on your localhost at `sam.example.localhost.org:29443`.  If you want
to run SAM on another domain, you will need to update your OAuth Web Application registration, your `etc/hosts` file,
and your SSL Certificate.

### Docker

To run the application you will need to download and [install Docker](https://www.docker.com/get-docker).

### Google Cloud Platform

This application uses [Google OAuth](https://developers.google.com/identity/protocols/OAuth2) to authenticate users.  In
order to configure OAuth in SAM, you will need to:

1. Have or create a [Google Cloud Project](https://cloud.google.com/resource-manager/docs/cloud-platform-resource-hierarchy#projects).
1. Generate a web application [OAuth Client ID](https://developers.google.com/identity/protocols/OAuth2WebServer#creatingcred) for your
 Google Project.  
    1. Add an "Authorized JavaScript origin" - `https://sam.example.localhost.org:29443`
    1. Add an "Authorized redirect URI" - `https://sam.example.localhost.org:29443/o2c.html`
1. In `docker/stand-alone/sam.conf`, modify:
    ```
    swagger {
      googleClientId = "REPLACE_ME_WITH_YOUR_GOOGLE_OAUTH_CLIENT_ID"
      realm = "REPLACE_ME_WITH_THE_NAME_OF_YOUR_GOOGLE_PROJECT"
    }
    ```

### Update /etc/hosts

Add the following to your `/etc/hosts` file:

```127.0.0.1 sam.example.localhost.org```

### Generate SSL Certificate

Generate an SSL Certificate and copy the cert, key, and ca chain to:
```
config/server.crt
config/server.key
config/ca-bundle.crt
```

### Starting the application
1. Create an external docker network: 
    ```
    docker network create sam
    ```

1.  `cd` to the `docker/stand-alone` directory and run:
    ```
    docker-compose up
    ```
1. SAM should be running at: https://sam.example.localhost.org:29443/