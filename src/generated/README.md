# swagger-java-client

## Requirements

Building the API client library requires [Maven](https://maven.apache.org/) to be installed.

## Installation

To install the API client library to your local Maven repository, simply execute:

```shell
mvn install
```

To deploy it to a remote Maven repository instead, configure the settings of the repository and execute:

```shell
mvn deploy
```

Refer to the [official documentation](https://maven.apache.org/plugins/maven-deploy-plugin/usage.html) for more information.

### Maven users

Add this dependency to your project's POM:

```xml
<dependency>
    <groupId>io.swagger</groupId>
    <artifactId>swagger-java-client</artifactId>
    <version>1.0.0</version>
    <scope>compile</scope>
</dependency>
```

### Gradle users

Add this dependency to your project's build file:

```groovy
compile "io.swagger:swagger-java-client:1.0.0"
```

### Others

At first generate the JAR by executing:

    mvn package

Then manually install the following JARs:

* target/swagger-java-client-1.0.0.jar
* target/lib/*.jar

## Getting Started

Please follow the [installation](#installation) instruction and execute the following Java code:

```java

import io.swagger.client.*;
import io.swagger.client.auth.*;
import io.swagger.client.model.*;
import io.swagger.client.api.AdminApi;

import java.io.File;
import java.util.*;

public class AdminApiExample {

    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        
        // Configure OAuth2 access token for authorization: googleoauth
        OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
        googleoauth.setAccessToken("YOUR ACCESS TOKEN");

        AdminApi apiInstance = new AdminApi();
        String email = "email_example"; // String | Email address of user to check
        try {
            UserStatus result = apiInstance.adminGetUserByEmail(email);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling AdminApi#adminGetUserByEmail");
            e.printStackTrace();
        }
    }
}

```

## Documentation for API Endpoints

All URIs are relative to *https://localhost*

Class | Method | HTTP request | Description
------------ | ------------- | ------------- | -------------
*AdminApi* | [**adminGetUserByEmail**](docs/AdminApi.md#adminGetUserByEmail) | **GET** /api/admin/user/email/{email} | gets the registration status of the user, by email
*AdminApi* | [**adminGetUserStatus**](docs/AdminApi.md#adminGetUserStatus) | **GET** /api/admin/user/{userId} | gets the registration status of the user, by user id
*AdminApi* | [**deletePet**](docs/AdminApi.md#deletePet) | **DELETE** /api/admin/user/{userId}/petServiceAccount | deletes a user&#39;s pet service account
*AdminApi* | [**disableUser**](docs/AdminApi.md#disableUser) | **PUT** /api/admin/user/{userId}/disable | disables the specified user
*AdminApi* | [**enableUser**](docs/AdminApi.md#enableUser) | **PUT** /api/admin/user/{userId}/enable | enables the specified user
*ResourcesApi* | [**createResource**](docs/ResourcesApi.md#createResource) | **POST** /api/resource/{resourceTypeName}/{resourceId} | Create a new resource
*ResourcesApi* | [**deleteResource**](docs/ResourcesApi.md#deleteResource) | **DELETE** /api/resource/{resourceTypeName}/{resourceId} | Delete a resource
*ResourcesApi* | [**listResourcePolicies**](docs/ResourcesApi.md#listResourcePolicies) | **GET** /api/resource/{resourceTypeName}/{resourceId}/policies | List the policies for a resource
*ResourcesApi* | [**listResourceTypes**](docs/ResourcesApi.md#listResourceTypes) | **GET** /api/resourceTypes | Lists available resource types
*ResourcesApi* | [**listResourcesAndPolicies**](docs/ResourcesApi.md#listResourcesAndPolicies) | **GET** /api/resource/{resourceTypeName} | List resources and policies for this resource for the caller
*ResourcesApi* | [**overwritePolicy**](docs/ResourcesApi.md#overwritePolicy) | **PUT** /api/resource/{resourceTypeName}/{resourceId}/policies/{policyName} | Overwrite a policy on a resource
*ResourcesApi* | [**resourceAction**](docs/ResourcesApi.md#resourceAction) | **GET** /api/resource/{resourceTypeName}/{resourceId}/action/{action} | Query if requesting user may perform the action
*ResourcesApi* | [**resourceRoles**](docs/ResourcesApi.md#resourceRoles) | **GET** /api/resource/{resourceTypeName}/{resourceId}/roles | Query for the list of roles that the requesting user has on the resource
*StatusApi* | [**getSystemStatus**](docs/StatusApi.md#getSystemStatus) | **GET** /status | gets system status
*UsersApi* | [**createUser**](docs/UsersApi.md#createUser) | **POST** /register/user | create current user in the system using login credentials
*UsersApi* | [**getPetServiceAccount**](docs/UsersApi.md#getPetServiceAccount) | **GET** /api/user/petServiceAccount | gets the pet service account for the specified user
*UsersApi* | [**getUserStatus**](docs/UsersApi.md#getUserStatus) | **GET** /register/user | gets the registration status of the logged in user


## Documentation for Models

 - [AccessPolicyMembership](docs/AccessPolicyMembership.md)
 - [AccessPolicyResponseEntry](docs/AccessPolicyResponseEntry.md)
 - [Enabled](docs/Enabled.md)
 - [ErrorReport](docs/ErrorReport.md)
 - [ResourceAndAccessPolicy](docs/ResourceAndAccessPolicy.md)
 - [ResourceRole](docs/ResourceRole.md)
 - [ResourceType](docs/ResourceType.md)
 - [StackTraceElement](docs/StackTraceElement.md)
 - [SubsystemStatus](docs/SubsystemStatus.md)
 - [SystemStatus](docs/SystemStatus.md)
 - [UserInfo](docs/UserInfo.md)
 - [UserPetServiceAccount](docs/UserPetServiceAccount.md)
 - [UserStatus](docs/UserStatus.md)


## Documentation for Authorization

Authentication schemes defined for the API:
### googleoauth

- **Type**: OAuth
- **Flow**: implicit
- **Authorization URL**: https://accounts.google.com/o/oauth2/auth
- **Scopes**: 
  - openid: open id authorization
  - email: email authorization
  - profile: profile authorization


## Recommendation

It's recommended to create an instance of `ApiClient` per thread in a multithreaded environment to avoid any potential issues.

## Author



