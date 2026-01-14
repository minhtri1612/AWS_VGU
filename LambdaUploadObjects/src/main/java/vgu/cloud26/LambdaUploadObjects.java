package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaUploadObjects implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String BUCKET_NAME = System.getenv("BUCKET_NAME");

    static {
        if (BUCKET_NAME == null) {
            throw new RuntimeException("Missing required environment variable: BUCKET_NAME");
        }
    }
    private static final Region REGION = Region.of(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"));

    // Lambda client to invoke LambdaAddPhotoDB
    private static final LambdaClient lambdaClient = LambdaClient.builder()
            .region(REGION)
            .build();

    private static final String ADD_PHOTO_DB_FUNC_NAME = System.getenv().getOrDefault("ADD_PHOTO_DB_FUNC_NAME",
            "LambdaAddPhotoDB");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            String requestBody = event.getBody();
            context.getLogger().log("Raw request body: " + requestBody);
            context.getLogger().log("Is base64 encoded: " + event.getIsBase64Encoded());

            // Decode base64 if necessary - check if body looks like base64
            if (requestBody != null && !requestBody.startsWith("{") && requestBody.matches("^[A-Za-z0-9+/]*={0,2}$")) {
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
                    requestBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                    context.getLogger().log("Decoded base64 request body: " + requestBody);
                } catch (Exception e) {
                    context.getLogger().log("Failed to decode base64: " + e.getMessage());
                }
            }

            // Handle both direct calls and calls through entry point
            JSONObject bodyJSON;
            if (requestBody != null && requestBody.startsWith("{")) {
                try {
                    // Try to parse as direct JSON first
                    bodyJSON = new JSONObject(requestBody);
                    // Check if this looks like a wrapped request from entry point
                    if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
                        // This is a wrapped request from entry point, extract the actual body
                        String actualBody = bodyJSON.getString("body");
                        context.getLogger().log("Extracted body from entry point wrapper: " + actualBody);
                        bodyJSON = new JSONObject(actualBody);
                    }
                } catch (Exception e) {
                    context.getLogger().log("Failed to parse request body: " + e.getMessage());
                    bodyJSON = new JSONObject("{}");
                }
            } else {
                bodyJSON = new JSONObject("{}");
            }
            context.getLogger().log("Parsed JSON keys: " + bodyJSON.keySet().toString());

            if (!bodyJSON.has("content")) {
                throw new Exception("Missing 'content' field in request body");
            }
            if (!bodyJSON.has("key")) {
                throw new Exception("Missing 'key' field in request body");
            }

            String content = bodyJSON.getString("content");
            String objName = bodyJSON.getString("key");

            context.getLogger().log("Content length: " + content.length() + ", Object name: " + objName);

            byte[] objBytes = Base64.getDecoder().decode(content.getBytes());

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(objName)
                    .build();

            S3Client s3Client = S3Client.builder()
                    .region(REGION)
                    .build();

            context.getLogger().log("Uploading to S3 bucket: " + BUCKET_NAME + ", key: " + objName + ", size: "
                    + objBytes.length + " bytes");

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(objBytes));

            context.getLogger().log("Upload to S3 completed successfully");

            response.setStatusCode(200);
            response.setBody("Object uploaded successfully");
            response.withIsBase64Encoded(false);

            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "text/plain");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type");
            response.setHeaders(headers);
        } catch (Exception e) {
            context.getLogger().log("Upload failed: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("Upload failed: " + e.getMessage());
            response.withIsBase64Encoded(false);

            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "text/plain");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type");
            response.setHeaders(headers);
        }

        return response;
    }

    // Helper method to call LambdaAddPhotoDB
    private void callAddPhotoDB(String filename, String description, Context context) {
        try {
            // Create the payload for LambdaAddPhotoDB
            JSONObject payload = new JSONObject();
            payload.put("key", filename);
            payload.put("description", description);

            // Wrap in APIGatewayProxyRequestEvent format (same as EntryPoint does)
            JSONObject lambdaEvent = new JSONObject();
            lambdaEvent.put("httpMethod", "POST");
            lambdaEvent.put("body", payload.toString());
            lambdaEvent.put("headers", new JSONObject());
            lambdaEvent.put("queryStringParameters", new JSONObject());

            String eventJson = lambdaEvent.toString();
            context.getLogger().log("Calling LambdaAddPhotoDB with payload: " + eventJson);

            InvokeRequest invokeRequest = InvokeRequest.builder()
                    .functionName(ADD_PHOTO_DB_FUNC_NAME)
                    .payload(SdkBytes.fromUtf8String(eventJson))
                    .invocationType("RequestResponse")
                    .build();

            InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
            ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
            String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();

            context.getLogger().log("LambdaAddPhotoDB response: " + responseString);
        } catch (Exception e) {
            context.getLogger().log("Error calling LambdaAddPhotoDB: " + e.getMessage());
            e.printStackTrace();
            // Don't throw - just log the error so upload still succeeds
        }
    }

}