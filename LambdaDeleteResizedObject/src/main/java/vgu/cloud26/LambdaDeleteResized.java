package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * WORKER Lambda - Delete from Resized S3 Bucket
 * Called by LambdaOrchestrateDeleteHandler
 */
public class LambdaDeleteResized
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final S3Client s3Client = S3Client.builder()
      .region(Region.AP_SOUTHEAST_2)
      .build();

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

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    try {
      // Decode base64 if needed
      String requestBody = event.getBody();
      if (requestBody != null && !requestBody.startsWith("{")) {
        try {
          byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
          requestBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
          logger.log("Decoded base64 request body");
        } catch (Exception e) {
          logger.log("Body is not base64 encoded: " + e.getMessage());
        }
      }

      // Parse JSON
      JSONObject bodyJSON;
      try {
        bodyJSON = new JSONObject(requestBody != null ? requestBody : "{}");
        if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
          String actualBody = bodyJSON.getString("body");
          if (actualBody != null && !actualBody.isEmpty()) {
            bodyJSON = new JSONObject(actualBody);
          }
        }
      } catch (Exception e) {
        logger.log("Error parsing JSON: " + e.getMessage());
        return createResponse(400, "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}");
      }

      String key = bodyJSON.optString("key", "");
      if (key.isEmpty()) {
        return createResponse(400, "{\"error\":\"Missing 'key' field\"}");
      }

      // Add "resized-" prefix
      String resizedKey = "resized-" + key;
      logger.log("Deleting from resized S3 bucket: " + resizedKey);

      // Delete from S3
      DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
          .bucket(RESIZED_BUCKET_NAME)
          .key(resizedKey)
          .build();

      s3Client.deleteObject(deleteRequest);
      logger.log("Successfully deleted from resized bucket: " + resizedKey);

      JSONObject response = new JSONObject();
      response.put("message", "Success: Deleted from resized S3 bucket");
      response.put("key", resizedKey);
      response.put("bucket", RESIZED_BUCKET_NAME);

      return createResponse(200, response.toString());

    } catch (Exception e) {
      logger.log("ERROR deleting from resized bucket (may not exist): " + e.getMessage());
      JSONObject errorResponse = new JSONObject();
      errorResponse.put("error", "Resized bucket delete failed (may not exist): " + e.getMessage());
      // Return 200 even if file doesn't exist (non-critical)
      return createResponse(200, errorResponse.toString());
    }
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(body)
        .withIsBase64Encoded(false);
  }
}