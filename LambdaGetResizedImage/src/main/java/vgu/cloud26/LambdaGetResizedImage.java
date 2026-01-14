package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class LambdaGetResizedImage
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RESIZED_BUCKET_NAME = System.getenv("RESIZED_BUCKET_NAME");

  static {
    if (RESIZED_BUCKET_NAME == null) {
      throw new RuntimeException("Missing required environment variable: RESIZED_BUCKET_NAME");
    }
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();

    // Handle OPTIONS preflight for CORS
    if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
      return createCorsResponse();
    }

    // 1. Parse the requested filename
    // Supports both POST body {"key":"..."} or Query String ?key=...
    String originalKey = "";
    try {
      // First check query string parameters (most common for GET requests)
      if (event.getQueryStringParameters() != null && event.getQueryStringParameters().containsKey("key")) {
        originalKey = event.getQueryStringParameters().get("key");
      }
      // Then check body if query string doesn't have key
      else if (event.getBody() != null && !event.getBody().isEmpty() && !event.getBody().equals("{}")) {
        String requestBody = event.getBody();
        
        // Check if body is wrapped (from LambdaEntryPoint) - unwrap it
        try {
          JSONObject wrapper = new JSONObject(requestBody);
          if (wrapper.has("body")) {
            requestBody = wrapper.getString("body");
            logger.log("Unwrapped body from LambdaEntryPoint: " + requestBody);
          }
        } catch (Exception wrapperEx) {
          // Not a wrapper, continue with original body
          logger.log("Body is not a wrapper, using as-is");
        }
        
        // Try to parse as JSON
        try {
          JSONObject body = new JSONObject(requestBody);
          if (body.has("key")) {
            originalKey = body.getString("key");
          }
        } catch (Exception jsonEx) {
          // Body is not valid JSON, try base64 decode (Lambda might encode it)
          logger.log("Body is not valid JSON, trying base64 decode: " + jsonEx.getMessage());
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
            String decodedBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
            JSONObject body = new JSONObject(decodedBody);
            if (body.has("key")) {
              originalKey = body.getString("key");
            }
          } catch (Exception base64Ex) {
            logger.log("Failed to decode base64 body: " + base64Ex.getMessage());
          }
        }
      }
    } catch (Exception e) {
      logger.log("Error parsing request: " + e.getMessage());
      return createErrorResponse(400, "Invalid Request: " + e.getMessage());
    }

    if (originalKey == null || originalKey.isEmpty()) {
      return createErrorResponse(400, "Missing 'key' parameter");
    }

    // 2. Calculate Resized Key
    String resizedKey = "resized-" + originalKey;

    try {
      S3Client s3 = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();

      // 3. Get Object from S3
      GetObjectRequest getRequest = GetObjectRequest.builder().bucket(RESIZED_BUCKET_NAME).key(resizedKey).build();

      ResponseBytes<GetObjectResponse> objectBytes = s3.getObjectAsBytes(getRequest);
      byte[] data = objectBytes.asByteArray();
      String contentType = objectBytes.response().contentType();

      // 4. Convert to Base64
      String base64Data = Base64.getEncoder().encodeToString(data);

      // 5. Return Image Response with CORS headers
      Map<String, String> headers = new java.util.HashMap<>();
      headers.put("Content-Type", contentType != null ? contentType : "image/jpeg");
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody(base64Data)
          .withIsBase64Encoded(true);

    } catch (Exception e) {
      logger.log("Error fetching resized image: " + e.getMessage());
      // Return a 404 so the browser shows a "broken image" icon instead of crashing
      return createErrorResponse(404, "Image not found");
    }
  }

  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "text/plain");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(message);
  }

  private APIGatewayProxyResponseEvent createCorsResponse() {
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withHeaders(headers)
        .withBody("");
  }
}
