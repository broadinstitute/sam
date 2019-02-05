# StatusApi

All URIs are relative to *https://localhost*

Method | HTTP request | Description
------------- | ------------- | -------------
[**getSystemStatus**](StatusApi.md#getSystemStatus) | **GET** /status | gets system status


<a name="getSystemStatus"></a>
# **getSystemStatus**
> SystemStatus getSystemStatus()

gets system status

### Example
```java
// Import classes:
//import io.swagger.client.ApiException;
//import io.swagger.client.api.StatusApi;


StatusApi apiInstance = new StatusApi();
try {
    SystemStatus result = apiInstance.getSystemStatus();
    System.out.println(result);
} catch (ApiException e) {
    System.err.println("Exception when calling StatusApi#getSystemStatus");
    e.printStackTrace();
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**SystemStatus**](SystemStatus.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

