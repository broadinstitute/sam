# AdminApi

All URIs are relative to *https://localhost*

Method | HTTP request | Description
------------- | ------------- | -------------
[**adminGetUserByEmail**](AdminApi.md#adminGetUserByEmail) | **GET** /api/admin/user/email/{email} | gets the registration status of the user, by email
[**adminGetUserStatus**](AdminApi.md#adminGetUserStatus) | **GET** /api/admin/user/{userId} | gets the registration status of the user, by user id
[**deletePet**](AdminApi.md#deletePet) | **DELETE** /api/admin/user/{userId}/petServiceAccount | deletes a user&#39;s pet service account
[**disableUser**](AdminApi.md#disableUser) | **PUT** /api/admin/user/{userId}/disable | disables the specified user
[**enableUser**](AdminApi.md#enableUser) | **PUT** /api/admin/user/{userId}/enable | enables the specified user


<a name="adminGetUserByEmail"></a>
# **adminGetUserByEmail**
> UserStatus adminGetUserByEmail(email)

gets the registration status of the user, by email

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.AdminApi;

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
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **email** | **String**| Email address of user to check |

### Return type

[**UserStatus**](UserStatus.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="adminGetUserStatus"></a>
# **adminGetUserStatus**
> UserStatus adminGetUserStatus(userId)

gets the registration status of the user, by user id

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.AdminApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

AdminApi apiInstance = new AdminApi();
String userId = "userId_example"; // String | User ID to check the status of
try {
    UserStatus result = apiInstance.adminGetUserStatus(userId);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling AdminApi#adminGetUserStatus");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **userId** | **String**| User ID to check the status of |

### Return type

[**UserStatus**](UserStatus.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="deletePet"></a>
# **deletePet**
> deletePet(userId)

deletes a user&#39;s pet service account

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.AdminApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

AdminApi apiInstance = new AdminApi();
String userId = "userId_example"; // String | User ID whose pet to delete
try {
    apiInstance.deletePet(userId);
} catch (ApiException e) {
    System.err.println("Exception when calling AdminApi#deletePet");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **userId** | **String**| User ID whose pet to delete |

### Return type

null (empty response body)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="disableUser"></a>
# **disableUser**
> UserStatus disableUser(userId)

disables the specified user

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.AdminApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

AdminApi apiInstance = new AdminApi();
String userId = "userId_example"; // String | User ID to disable
try {
    UserStatus result = apiInstance.disableUser(userId);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling AdminApi#disableUser");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **userId** | **String**| User ID to disable |

### Return type

[**UserStatus**](UserStatus.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="enableUser"></a>
# **enableUser**
> UserStatus enableUser(userId)

enables the specified user

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.AdminApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

AdminApi apiInstance = new AdminApi();
String userId = "userId_example"; // String | User ID to enable
try {
    UserStatus result = apiInstance.enableUser(userId);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling AdminApi#enableUser");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **userId** | **String**| User ID to enable |

### Return type

[**UserStatus**](UserStatus.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

