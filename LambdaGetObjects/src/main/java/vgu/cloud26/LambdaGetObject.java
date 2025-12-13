package vgu.cloud26;

import java.io.IOException;
import java.util.Base64;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

public class LambdaGetObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // 1. OPTIMIZATION: Static Client
    private static final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    // 2. CONFIGURATION: Environment Variable for Bucket
    private static final String BUCKET_NAME = System.getenv().getOrDefault("BUCKET_NAME", "minhtri-devops-cloud-getobjects");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        // Note: OPTIONS preflight is handled by Function URL CORS configuration
        String requestBody = request.getBody();
        
        context.getLogger().log("Raw request body: " + requestBody);
        
        // Case-insensitive header lookup
        String acceptHeader = null;
        String contentTypeHeader = null;
        if (request.getHeaders() != null) {
            for (java.util.Map.Entry<String, String> h : request.getHeaders().entrySet()) {
                if (h.getKey() == null) continue;
                String key = h.getKey();
                if (key.equalsIgnoreCase("Accept")) {
                    acceptHeader = h.getValue();
                } else if (key.equalsIgnoreCase("Content-Type")) {
                    contentTypeHeader = h.getValue();
                }
            }
        }
        
        context.getLogger().log("Accept header: " + acceptHeader);
        context.getLogger().log("Content-Type header: " + contentTypeHeader);
        
        // Check query parameters for explicit format request
        String formatParam = null;
        if (request.getQueryStringParameters() != null) {
            formatParam = request.getQueryStringParameters().get("format");
        }
        
        context.getLogger().log("Format parameter: " + formatParam);
        
        // If no body or empty body, choose between list and index based on query param or headers
        if (requestBody == null || requestBody.trim().isEmpty() || requestBody.equals("{}")) {
            // Check for explicit format parameter first
            if ("json".equals(formatParam)) {
                context.getLogger().log("format=json parameter detected, returning list of objects");
                return listObjects(context);
            }
            // JavaScript fetch with Content-Type: application/json should get JSON response
            else if (contentTypeHeader != null && contentTypeHeader.toLowerCase().contains("application/json")) {
                context.getLogger().log("Content-Type: application/json detected, returning list of objects");
                return listObjects(context);
            }
            // Browser request with Accept: text/html should get HTML
            else if (acceptHeader != null && acceptHeader.toLowerCase().contains("text/html")) {
                context.getLogger().log("Accept: text/html detected, returning index.html");
                return getSpecificObject("index.html", context);
            }
            // Default: if no clear indication, return HTML for browser compatibility
            else {
                context.getLogger().log("No clear indication, defaulting to index.html for browser");
                return getSpecificObject("index.html", context);
            }
        }
        
        // Check if the body is base64 encoded (API Gateway does this with binary_media_types)
        if (requestBody != null && !requestBody.startsWith("{")) {
            try {
                // Decode base64
                byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
                requestBody = new String(decodedBytes, StandardCharsets.UTF_8);
                context.getLogger().log("Decoded body: " + requestBody);
            } catch (Exception e) {
                context.getLogger().log("Failed to decode base64: " + e.getMessage());
            }
        }
        
        JSONObject bodyJSON = new JSONObject(requestBody);
        
        // Safety check: ensure key exists
        String key = "index.html"; 
        if (bodyJSON.has("key")) {
             key = bodyJSON.getString("key");
        }

        return getSpecificObject(key, context);
    }
    
    private APIGatewayProxyResponseEvent getSpecificObject(String key, Context context) {
        String mimeType = "application/octet-stream";
        String body = "";
        boolean isBase64 = true;
        int statusCode = 200;

        try {
            // Check metadata directly instead of listing all objects
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            HeadObjectResponse meta = s3Client.headObject(headRequest);
            long objectSize = meta.contentLength();
            int maxSize = 10 * 1024 * 1024; // 10MB

            if (objectSize < maxSize) {
                // Determine Mime Type
                String[] parts = key.split("\\.");
                if (parts.length > 1) {
                    String ext = parts[parts.length - 1].toLowerCase();
                    if (ext.equals("png")) mimeType = "image/png";
                    else if (ext.equals("html")) mimeType = "text/html";
                    else if (ext.equals("jpg") || ext.equals("jpeg")) mimeType = "image/jpeg";
                    else if (ext.equals("txt")) mimeType = "text/plain";
                }

                // Get Object
                GetObjectRequest s3Request = GetObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(key)
                        .build();

                try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(s3Request)) {
                    byte[] buffer = s3Response.readAllBytes();

                    // For HTML/text files return plain body (no base64) so browsers render correctly
                    if (mimeType.startsWith("text/html") || mimeType.startsWith("text/plain")) {
                        body = new String(buffer, StandardCharsets.UTF_8);
                        isBase64 = false;
                    } else {
                        body = Base64.getEncoder().encodeToString(buffer);
                        isBase64 = true;
                    }
                }
            } else {
                context.getLogger().log("File too large: " + objectSize);
                statusCode = 413; // Payload Too Large
            }

        } catch (S3Exception e) {
            context.getLogger().log("S3 Error: " + e.getMessage());
            statusCode = 404; // Not Found
        } catch (IOException e) {
            context.getLogger().log("IO Error: " + e.getMessage());
            statusCode = 500;
        }

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);
        response.withIsBase64Encoded(isBase64);
        
        // Note: CORS headers are handled by Function URL configuration
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", mimeType);
        response.setHeaders(headers);
        
        return response;
    }
    
    private APIGatewayProxyResponseEvent listObjects(Context context) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .build();
            
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();
            
            // Create JSON array for frontend
            List<JSONObject> objectList = new ArrayList<>();
            for (S3Object obj : objects) {
                JSONObject objJson = new JSONObject();
                objJson.put("key", obj.key());
                objJson.put("size", obj.size());
                objJson.put("lastModified", obj.lastModified().toString());
                objectList.add(objJson);
            }
            
            String jsonResponse = new org.json.JSONArray(objectList).toString();
            
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setBody(jsonResponse);
            response.withIsBase64Encoded(false);
            
            // Set headers with CORS support
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.setHeaders(headers);
            
            return response;
            
        } catch (S3Exception e) {
            context.getLogger().log("S3 Error listing objects: " + e.getMessage());
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(500);
            response.setBody("[]");
            response.withIsBase64Encoded(false);
            
            // Note: CORS headers are handled by Function URL configuration
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "application/json");
            response.setHeaders(headers);
            
            return response;
        }
    }
}