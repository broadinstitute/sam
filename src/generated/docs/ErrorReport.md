
# ErrorReport

## Properties
Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**source** | **String** | service causing error | 
**message** | **String** | what went wrong | 
**exceptionClass** | **String** | class of exception thrown |  [optional]
**statusCode** | **Integer** | HTTP status code |  [optional]
**causes** | [**List&lt;ErrorReport&gt;**](ErrorReport.md) | errors triggering this one | 
**stackTrace** | [**List&lt;StackTraceElement&gt;**](StackTraceElement.md) | stack trace | 



