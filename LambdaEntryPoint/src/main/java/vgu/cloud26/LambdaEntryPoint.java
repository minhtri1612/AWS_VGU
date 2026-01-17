package vgu.cloud26;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaEntryPoint implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // 1. OPTIMIZATION: Static Client
    private static final LambdaClient lambdaClient = LambdaClient.builder()
            .region(Region.of("ap-southeast-2"))
            .build();
    
    // 2. CONFIGURATION: Get function names from Env Vars
    private static final String DELETE_FUNC_NAME = System.getenv().getOrDefault("DELETE_FUNC_NAME", "LambdaOrchestrateDeleteHandler");
    private static final String GET_FUNC_NAME = System.getenv().getOrDefault("GET_FUNC_NAME", "LambdaGetObjects");
    private static final String UPLOAD_FUNC_NAME = System.getenv().getOrDefault("UPLOAD_FUNC_NAME", "LambdaOrchestrateUploadHandler");
    private static final String LIST_FUNC_NAME = System.getenv().getOrDefault("LIST_FUNC_NAME", "LambdaGetListOfObjects");
    private static final String GET_RESIZED_FUNC_NAME = System.getenv().getOrDefault("GET_RESIZED_FUNC_NAME", "LambdaGetResizedImage");
    private static final String GET_PHOTOS_DB_FUNC_NAME = System.getenv().getOrDefault("GET_PHOTOS_DB_FUNC_NAME", "LambdaGetPhotosDB");
    private static final String ADD_PHOTO_DB_FUNC_NAME = System.getenv().getOrDefault("ADD_PHOTO_DB_FUNC_NAME", "LambdaAddPhotoDB");

    public LambdaEntryPoint() {
    }   
    
    public String callLambda(String functionName, String payload, LambdaLogger logger) {
        String message;
        InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String(payload))
                .invocationType("RequestResponse") 
                .build();

        try {
            InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
            ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
            String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();

            try {
                JSONObject responseObject = new JSONObject(responseString);
                
                if (responseObject.has("body")) {
                    message = responseObject.getString("body");
                } else {
                    message = responseString;
                }
            } catch (Exception jsonException) {
                // If it's not valid JSON, return the raw response
                message = responseString;
            }
            
            logger.log("Response from " + functionName + ": " + message);
            return message;

        } catch (AwsServiceException | SdkClientException e) {
            message = "Error calling " + functionName + ": " + e.getMessage();
            logger.log(message);
        }
        return message;
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context cntxt) {
        LambdaLogger logger = cntxt.getLogger();
        logger.log("Invoking LambdaEntryPoint");

        // Handle OPTIONS request for CORS preflight
        String httpMethod = event.getHttpMethod();
        if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setBody("");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.setHeaders(headers);
            return response;
        }

        // 3. LOGIC: Determine action - query string has PRIORITY over HTTP method
        String action = "get"; // default
        boolean queryStringMatched = false;
        
        // PRIORITY 1: Check query string parameters FIRST (before HTTP method)
        if (event.getQueryStringParameters() != null) {
            if (event.getQueryStringParameters().containsKey("action")) {
                action = event.getQueryStringParameters().get("action");
                queryStringMatched = true;
            } else if (event.getQueryStringParameters().containsKey("format")) {
                String format = event.getQueryStringParameters().get("format");
                if (format.equals("json")) {
                    action = "list";
                    queryStringMatched = true;
                } else if (format.equals("resized")) {
                    action = "get_resized"; // Handle ?format=resized regardless of HTTP method
                    queryStringMatched = true;
                } else if (format.equals("photos") || format.equals("db")) {
                    action = "get_photos_db";
                    queryStringMatched = true;
                }
            } else if (event.getQueryStringParameters().containsKey("resized")) {
                action = "get_resized";
                queryStringMatched = true;
            } else if (event.getQueryStringParameters().containsKey("photos")) {
                action = "get_photos_db";
                queryStringMatched = true;
            }
        }
        
        // PRIORITY 2: If no query string matched, use HTTP method
        if (!queryStringMatched) {
            switch (httpMethod.toUpperCase()) {
                case "POST":
                    action = "upload";
                    break;
                case "DELETE":
                    action = "delete";
                    break;
                case "PUT":
                    action = "get";
                    break;
                case "GET":
                default:
                    action = "get";
                    break;
            }
        }
        
        logger.log("HTTP Method: " + httpMethod + ", Determined action: " + action);

        // 4. PAYLOAD: Prepare payload based on action
        String payload;
        // Create a proper APIGatewayProxyRequestEvent structure for the target Lambda
        JSONObject lambdaEvent = new JSONObject();
        lambdaEvent.put("httpMethod", httpMethod);

        if (event.getBody() != null && !event.getBody().isEmpty()) {
            lambdaEvent.put("body", event.getBody());
        } else {
            lambdaEvent.put("body", "{}");
        }

        // Forward headers so downstream can detect content-type / accept
        if (event.getHeaders() != null && !event.getHeaders().isEmpty()) {
            lambdaEvent.put("headers", new JSONObject(event.getHeaders()));
        }

        // Add other necessary fields
        if (event.getQueryStringParameters() != null) {
            lambdaEvent.put("queryStringParameters", new JSONObject(event.getQueryStringParameters()));
        }
        
        payload = lambdaEvent.toString();

        // 5. ROUTING: Choose the correct function
        String functionName;
        if (action.equalsIgnoreCase("delete")) {
            functionName = DELETE_FUNC_NAME;
        } else if (action.equalsIgnoreCase("upload")) {
            functionName = UPLOAD_FUNC_NAME;
        } else if (action.equalsIgnoreCase("list")) {
            functionName = LIST_FUNC_NAME;
        } else if (action.equalsIgnoreCase("get_resized")) {
            functionName = GET_RESIZED_FUNC_NAME;
        } else if (action.equalsIgnoreCase("get_photos_db")) {
            functionName = GET_PHOTOS_DB_FUNC_NAME;
        } else if (action.equalsIgnoreCase("add_photo_db")) {
            functionName = ADD_PHOTO_DB_FUNC_NAME;
        } else {
            functionName = GET_FUNC_NAME;
        }
        
        logger.log("Routing action '" + action + "' to function: " + functionName);
        logger.log("Payload being sent to " + functionName + ": " + payload);

        // Call the target Lambda and get the full response
        InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String(payload))
                .invocationType("RequestResponse") 
                .build();

        try {
            InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
            ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
            String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();
            
            logger.log("Raw response from " + functionName + ": " + responseString);
            
            // Parse the response as a complete APIGatewayProxyResponseEvent
            try {
                JSONObject responseObject = new JSONObject(responseString);
                
                APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
                
                // Forward status code
                if (responseObject.has("statusCode")) {
                    response.setStatusCode(responseObject.getInt("statusCode"));
                } else {
                    response.setStatusCode(200);
                }
                
                // Forward body
                if (responseObject.has("body")) {
                    String bodyStr = responseObject.getString("body");
                    response.setBody(bodyStr != null ? bodyStr : "{\"message\":\"Success\"}");
                } else {
                    // If no body field, use the entire response as body or default message
                    response.setBody(responseObject.toString());
                }
                
                // Forward headers and ensure CORS headers are always present
                java.util.Map<String, String> headerMap = new java.util.HashMap<>();
                if (responseObject.has("headers")) {
                    JSONObject headers = responseObject.getJSONObject("headers");
                    for (String key : headers.keySet()) {
                        headerMap.put(key, headers.getString(key));
                    }
                }
                // Always add CORS headers (override if already present)
                headerMap.put("Access-Control-Allow-Origin", "*");
                headerMap.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                headerMap.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
                response.setHeaders(headerMap);
                
                // Forward isBase64Encoded
                if (responseObject.has("isBase64Encoded")) {
                    response.setIsBase64Encoded(responseObject.getBoolean("isBase64Encoded"));
                }
                
                return response;
                
            } catch (Exception jsonException) {
                logger.log("Failed to parse response as JSON, returning as plain text: " + jsonException.getMessage());
                // Fallback to plain response with CORS headers
                java.util.Map<String, String> corsHeaders = new java.util.HashMap<>();
                corsHeaders.put("content-type", "text/plain");
                corsHeaders.put("Access-Control-Allow-Origin", "*");
                corsHeaders.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                corsHeaders.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(corsHeaders)
                        .withBody(responseString)
                        .withIsBase64Encoded(false);
            }
            
        } catch (AwsServiceException | SdkClientException e) {
            logger.log("Error calling " + functionName + ": " + e.getMessage());
            java.util.Map<String, String> errorHeaders = new java.util.HashMap<>();
            errorHeaders.put("content-type", "application/json");
            errorHeaders.put("Access-Control-Allow-Origin", "*");
            errorHeaders.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            errorHeaders.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(errorHeaders)
                    .withBody("{\"error\":\"" + e.getMessage() + "\"}")
                    .withIsBase64Encoded(false);
        }
    }
}