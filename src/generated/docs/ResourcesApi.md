# ResourcesApi

All URIs are relative to *https://localhost*

Method | HTTP request | Description
------------- | ------------- | -------------
[**createResource**](ResourcesApi.md#createResource) | **POST** /api/resource/{resourceTypeName}/{resourceId} | Create a new resource
[**deleteResource**](ResourcesApi.md#deleteResource) | **DELETE** /api/resource/{resourceTypeName}/{resourceId} | Delete a resource
[**listResourcePolicies**](ResourcesApi.md#listResourcePolicies) | **GET** /api/resource/{resourceTypeName}/{resourceId}/policies | List the policies for a resource
[**listResourceTypes**](ResourcesApi.md#listResourceTypes) | **GET** /api/resourceTypes | Lists available resource types
[**listResourcesAndPolicies**](ResourcesApi.md#listResourcesAndPolicies) | **GET** /api/resource/{resourceTypeName} | List resources and policies for this resource for the caller
[**overwritePolicy**](ResourcesApi.md#overwritePolicy) | **PUT** /api/resource/{resourceTypeName}/{resourceId}/policies/{policyName} | Overwrite a policy on a resource
[**resourceAction**](ResourcesApi.md#resourceAction) | **GET** /api/resource/{resourceTypeName}/{resourceId}/action/{action} | Query if requesting user may perform the action
[**resourceRoles**](ResourcesApi.md#resourceRoles) | **GET** /api/resource/{resourceTypeName}/{resourceId}/roles | Query for the list of roles that the requesting user has on the resource


<a name="createResource"></a>
# **createResource**
> createResource(resourceTypeName, resourceId)

Create a new resource

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of resource to create
String resourceId = "resourceId_example"; // String | Id of resource to create, must be unique for all resources of given type
try {
    apiInstance.createResource(resourceTypeName, resourceId);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#createResource");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of resource to create |
 **resourceId** | **String**| Id of resource to create, must be unique for all resources of given type |

### Return type

null (empty response body)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="deleteResource"></a>
# **deleteResource**
> deleteResource(resourceTypeName, resourceId)

Delete a resource

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of the resource
String resourceId = "resourceId_example"; // String | Id of the resource
try {
    apiInstance.deleteResource(resourceTypeName, resourceId);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#deleteResource");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of the resource |
 **resourceId** | **String**| Id of the resource |

### Return type

null (empty response body)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="listResourcePolicies"></a>
# **listResourcePolicies**
> List&lt;AccessPolicyResponseEntry&gt; listResourcePolicies(resourceTypeName, resourceId)

List the policies for a resource

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of resource
String resourceId = "resourceId_example"; // String | Id of resource
try {
    List<AccessPolicyResponseEntry> result = apiInstance.listResourcePolicies(resourceTypeName, resourceId);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#listResourcePolicies");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of resource |
 **resourceId** | **String**| Id of resource |

### Return type

[**List&lt;AccessPolicyResponseEntry&gt;**](AccessPolicyResponseEntry.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="listResourceTypes"></a>
# **listResourceTypes**
> List&lt;ResourceType&gt; listResourceTypes()

Lists available resource types

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
try {
    List<ResourceType> result = apiInstance.listResourceTypes();
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#listResourceTypes");
    e.printStackTrace();
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**List&lt;ResourceType&gt;**](ResourceType.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="listResourcesAndPolicies"></a>
# **listResourcesAndPolicies**
> List&lt;ResourceAndAccessPolicy&gt; listResourcesAndPolicies(resourceTypeName)

List resources and policies for this resource for the caller

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of the resource
try {
    List<ResourceAndAccessPolicy> result = apiInstance.listResourcesAndPolicies(resourceTypeName);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#listResourcesAndPolicies");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of the resource |

### Return type

[**List&lt;ResourceAndAccessPolicy&gt;**](ResourceAndAccessPolicy.md)

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="overwritePolicy"></a>
# **overwritePolicy**
> List&lt;String&gt; overwritePolicy(resourceTypeName, resourceId, policyName, policyCreate)

Overwrite a policy on a resource

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of resource
String resourceId = "resourceId_example"; // String | Id of resource
String policyName = "policyName_example"; // String | Name of the policy
AccessPolicyMembership policyCreate = new AccessPolicyMembership(); // AccessPolicyMembership | The details of the policy
try {
    List<String> result = apiInstance.overwritePolicy(resourceTypeName, resourceId, policyName, policyCreate);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#overwritePolicy");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of resource |
 **resourceId** | **String**| Id of resource |
 **policyName** | **String**| Name of the policy |
 **policyCreate** | [**AccessPolicyMembership**](AccessPolicyMembership.md)| The details of the policy |

### Return type

**List&lt;String&gt;**

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="resourceAction"></a>
# **resourceAction**
> Boolean resourceAction(resourceTypeName, resourceId, action)

Query if requesting user may perform the action

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of resource to query
String resourceId = "resourceId_example"; // String | Id of resource to query
String action = "action_example"; // String | Action to perform
try {
    Boolean result = apiInstance.resourceAction(resourceTypeName, resourceId, action);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#resourceAction");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of resource to query |
 **resourceId** | **String**| Id of resource to query |
 **action** | **String**| Action to perform |

### Return type

**Boolean**

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a name="resourceRoles"></a>
# **resourceRoles**
> List&lt;String&gt; resourceRoles(resourceTypeName, resourceId)

Query for the list of roles that the requesting user has on the resource

### Example
```java
// Import classes:
//import io.swagger.client.ApiClient;
//import io.swagger.client.ApiException;
//import io.swagger.client.Configuration;
//import io.swagger.client.auth.*;
//import io.swagger.client.api.ResourcesApi;

ApiClient defaultClient = Configuration.getDefaultApiClient();

// Configure OAuth2 access token for authorization: googleoauth
OAuth googleoauth = (OAuth) defaultClient.getAuthentication("googleoauth");
googleoauth.setAccessToken("YOUR ACCESS TOKEN");

ResourcesApi apiInstance = new ResourcesApi();
String resourceTypeName = "resourceTypeName_example"; // String | Type of resource to query
String resourceId = "resourceId_example"; // String | Id of resource to query
try {
    List<String> result = apiInstance.resourceRoles(resourceTypeName, resourceId);
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling ResourcesApi#resourceRoles");
    e.printStackTrace();
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **resourceTypeName** | **String**| Type of resource to query |
 **resourceId** | **String**| Id of resource to query |

### Return type

**List&lt;String&gt;**

### Authorization

[googleoauth](../README.md#googleoauth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

