package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

/**
 * DELETE ORCHESTRATOR - Only coordinates worker Lambdas
 * Does NOT perform delete operations itself
 * Pattern: Orchestrator -> Workers (like LambdaOrchestrateUploadHandler)
 */
public class LambdaOrchestrateDeleteHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  private final SsmClient ssmClient;

  // Worker Lambda function names from environment variables
  // LambdaDeleteObjects handles both S3 and DB deletion, so we only need to call it once
  private static final String DELETE_OBJECTS_FUNC = System.getenv()
      .getOrDefault("DELETE_OBJECTS_FUNC", "LambdaDeleteObjects");
  private static final String DELETE_RESIZED_FUNC = System.getenv()
      .getOrDefault("DELETE_RESIZED_FUNC", "LambdaDeleteResizedObject");

  // Database config for ownership verification
  private static final String RDS_HOSTNAME = System.getenv("RDS_HOSTNAME");
  private static final String RDS_PORT = System.getenv("RDS_PORT");
  private static final String DB_USER = System.getenv("DB_USER");
  private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");
  private static final String DB_NAME = System.getenv("DB_NAME");

  static {
    if (RDS_HOSTNAME == null || RDS_PORT == null || DB_USER == null ||
        DB_PASSWORD == null || DB_NAME == null) {
      throw new RuntimeException("Missing required RDS environment variables");
    }
  }

  public LambdaOrchestrateDeleteHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.ssmClient = SsmClient.builder().region(Region.AP_SOUTHEAST_2).build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();

    Map<String, String> corsHeaders = new HashMap<>();
    corsHeaders.put("Access-Control-Allow-Origin", "*");
    corsHeaders.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    corsHeaders.put("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Amz-Date, X-Api-Key");
    corsHeaders.put("Access-Control-Max-Age", "3600");

    try {
      // Handle OPTIONS preflight
      String httpMethod = event.getHttpMethod();
      if (httpMethod != null && "OPTIONS".equalsIgnoreCase(httpMethod)) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withHeaders(corsHeaders)
            .withBody("");
      }

      // Decode base64 if needed
      String userRequestBody = event.getBody();
      if (userRequestBody != null && !userRequestBody.isEmpty() && !userRequestBody.equals("{}")) {
        if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(userRequestBody);
            userRequestBody = new String(decodedBytes, StandardCharsets.UTF_8);
            logger.log("Decoded base64 request body");
          } catch (Exception e) {
            logger.log("Failed to decode base64: " + e.getMessage());
          }
        }
        if (!userRequestBody.startsWith("{")) {
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(userRequestBody);
            userRequestBody = new String(decodedBytes, StandardCharsets.UTF_8);
            logger.log("Decoded base64 request body (fallback)");
          } catch (Exception e) {
            logger.log("Failed to decode base64: " + e.getMessage());
          }
        }
      }

      // Parse request body
      JSONObject bodyJSON;
      try {
        bodyJSON = new JSONObject(userRequestBody != null ? userRequestBody : "{}");
        if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
          String actualBody = bodyJSON.getString("body");
          if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
            bodyJSON = new JSONObject(actualBody);
          }
        }
      } catch (Exception e) {
        logger.log("Error parsing request body: " + e.getMessage());
        return createErrorResponse(corsHeaders, 400, "Invalid JSON in request body: " + e.getMessage());
      }

      String key = bodyJSON.optString("key", "");
      if (key.isEmpty()) {
        return createErrorResponse(corsHeaders, 400, "Missing 'key' field in request body");
      }

      // SECURITY: Verify token and ownership
      String token = bodyJSON.optString("token", null);
      String email = bodyJSON.optString("email", null);

      if (token == null || token.isEmpty() || email == null || email.isEmpty()) {
        logger.log("Missing token or email - token: " + (token != null ? "present" : "null") + ", email: " + (email != null ? "present" : "null"));
        return createErrorResponse(corsHeaders, 403, "Missing token or email");
      }

      try {
        if (!verifyTokenWithHash(email, token, logger)) {
          logger.log("Token verification failed for email: " + email);
          return createErrorResponse(corsHeaders, 403, "Invalid token");
        }
      } catch (Exception e) {
        logger.log("Exception during token verification: " + e.getMessage());
        e.printStackTrace();
        return createErrorResponse(corsHeaders, 500, "Token verification error: " + e.getMessage());
      }

      try {
        if (!verifyPhotoOwnership(key, email, logger)) {
          logger.log("Ownership verification failed - key: " + key + ", email: " + email);
          return createErrorResponse(corsHeaders, 403, "You don't have permission to delete this photo");
        }
      } catch (Exception e) {
        logger.log("Exception during ownership verification: " + e.getMessage());
        e.printStackTrace();
        return createErrorResponse(corsHeaders, 500, "Ownership verification error: " + e.getMessage());
      }

      logger.log("Starting delete workflow for key: " + key);

      // Prepare payload for worker Lambdas (same format as upload orchestrator)
      JSONObject lambdaEvent = new JSONObject();
      lambdaEvent.put("httpMethod", "DELETE");
      lambdaEvent.put("body", userRequestBody != null ? userRequestBody : "{}");
      JSONObject eventHeaders = new JSONObject();
      eventHeaders.put("Content-Type", "application/json");
      lambdaEvent.put("headers", eventHeaders);
      String workerPayload = lambdaEvent.toString();

      JSONObject results = new JSONObject();

      // STEP 1: Delete from S3 and DB (LambdaDeleteObjects handles both)
      logger.log("Step 1: Deleting from S3 and DB...");
      String deleteObjectsResult = callLambda(DELETE_OBJECTS_FUNC, workerPayload, logger);
      results.put("Activity_1_Original_S3_and_DB", deleteObjectsResult);
      
      // Check if delete failed
      if (deleteObjectsResult.contains("Error") || deleteObjectsResult.contains("Failed") || deleteObjectsResult.contains("error")) {
        logger.log("Delete from S3/DB failed, aborting workflow");
        results.put("Activity_2_Resized_S3", "Skipped (S3/DB delete failed)");
        return createResultsResponse(500, results, corsHeaders);
      }

      // STEP 2: Delete resized image (non-critical, continue even if fails)
      logger.log("Step 2: Deleting resized image...");
      String deleteResizedResult = callLambda(DELETE_RESIZED_FUNC, workerPayload, logger);
      results.put("Activity_2_Resized_S3", deleteResizedResult);
      
      // Resized delete failure is non-critical, don't mark as error
      if (deleteResizedResult.contains("Error") || deleteResizedResult.contains("Failed")) {
        logger.log("Resized delete failed (non-critical): " + deleteResizedResult);
      }

      logger.log("All delete activities completed");

      // Return Combined Report
      return createResultsResponse(200, results, corsHeaders);

    } catch (Exception e) {
      logger.log("ERROR in delete orchestrator: " + e.getMessage());
      e.printStackTrace();
      return createErrorResponse(corsHeaders, 500, "Delete orchestrator failed: " + e.getMessage());
    }
  }

  // Helper to call worker Lambda (same as upload orchestrator)
  private String callLambda(String functionName, String payload, LambdaLogger logger) {
    try {
      InvokeRequest invokeRequest = InvokeRequest.builder()
          .functionName(functionName)
          .payload(SdkBytes.fromUtf8String(payload))
          .invocationType("RequestResponse") // Synchronous
          .build();

      InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
      
      // Check if Lambda execution failed
      if (invokeResult.functionError() != null && !invokeResult.functionError().isEmpty()) {
        logger.log("Lambda function error: " + invokeResult.functionError());
        ByteBuffer errorPayload = invokeResult.payload().asByteBuffer();
        String errorResponse = StandardCharsets.UTF_8.decode(errorPayload).toString();
        logger.log("Error response: " + errorResponse);
        return "Failed: Lambda execution failed: " + invokeResult.functionError();
      }
      
      ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
      String jsonResponse = StandardCharsets.UTF_8.decode(responsePayload).toString();

      // Parse the worker's JSON response to get the clean message body
      try {
        JSONObject responseObject = new JSONObject(jsonResponse);
        if (responseObject.has("body")) {
          return responseObject.getString("body");
        }
      } catch (Exception e) {
        // If not JSON, return as-is
      }
      return jsonResponse;
    } catch (Exception e) {
      logger.log("Error invoking " + functionName + ": " + e.getMessage());
      e.printStackTrace();
      return "Failed: " + e.getMessage();
    }
  }

  // SECURITY: Verify token using HMAC-SHA256
  private boolean verifyTokenWithHash(String email, String token, LambdaLogger logger) {
    if (email == null || email.isEmpty() || token == null || token.isEmpty()) {
      logger.log("Missing email or token - email: " + (email != null ? email : "null") + ", token: " + (token != null ? "present" : "null"));
      return false;
    }

    try {
      String secretKey = getSecretKeyFromParameterStore(logger);
      if (secretKey == null || secretKey.isEmpty()) {
        logger.log("Failed to get SECRET_KEY from Parameter Store");
        return false;
      }

      String generatedToken = generateSecureToken(email, secretKey, logger);
      if (generatedToken == null) {
        logger.log("Error generating token for comparison");
        return false;
      }

      boolean isValid = generatedToken.equals(token);
      logger.log("Token verification for " + email + ": isValid=" + isValid + ", generated=" + generatedToken.substring(0, Math.min(10, generatedToken.length())) + "... , received=" + token.substring(0, Math.min(10, token.length())) + "...");
      return isValid;

    } catch (Exception e) {
      logger.log("Error verifying token: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }

  private String generateSecureToken(String email, String secretKey, LambdaLogger logger) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec = new SecretKeySpec(
          secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(secretKeySpec);
      byte[] hmacBytes = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hmacBytes);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      logger.log("Error generating token: " + e.getMessage());
      return null;
    }
  }

  // SECURITY: Verify photo ownership
  private boolean verifyPhotoOwnership(String key, String email, LambdaLogger logger) throws Exception {
    String jdbcUrl = "jdbc:mysql://" + RDS_HOSTNAME + ":" + RDS_PORT + "/" + DB_NAME;
    Properties props = new Properties();
    props.setProperty("useSSL", "true");
    props.setProperty("user", DB_USER);
    props.setProperty("password", DB_PASSWORD);

    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
      String sql = "SELECT COUNT(*) as count FROM Photos WHERE S3Key = ? AND Email = ?";
      try (PreparedStatement st = conn.prepareStatement(sql)) {
        st.setString(1, key);
        st.setString(2, email);
        try (java.sql.ResultSet rs = st.executeQuery()) {
          if (rs.next() && rs.getInt("count") > 0) {
            logger.log("Ownership verified: " + key + " belongs to " + email);
            return true;
          } else {
            logger.log("Photo " + key + " does not belong to " + email);
            return false;
          }
        }
      }
    }
  }

  private String getSecretKeyFromParameterStore(LambdaLogger logger) {
    try {
      GetParameterRequest parameterRequest = GetParameterRequest.builder()
          .name("keytokenhash")
          .withDecryption(true)
          .build();

      GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
      String secretKey = parameterResponse.parameter().value();
      logger.log("Successfully retrieved SECRET_KEY from Parameter Store");
      return secretKey;

    } catch (SsmException e) {
      logger.log("Error retrieving SECRET_KEY: " + e.getMessage());
      String fallbackKey = System.getenv("SECRET_KEY");
      if (fallbackKey != null && !fallbackKey.isEmpty()) {
        logger.log("Using SECRET_KEY from environment variable");
        return fallbackKey;
      }
      return null;
    }
  }

  // Helper to create results response (same as upload orchestrator)
  private APIGatewayProxyResponseEvent createResultsResponse(int statusCode, JSONObject results, Map<String, String> corsHeaders) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.putAll(corsHeaders);

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(results.toString(4)) // Pretty print JSON
        .withIsBase64Encoded(false);
  }

  private APIGatewayProxyResponseEvent createErrorResponse(
      Map<String, String> corsHeaders, int statusCode, String errorMessage) {
    JSONObject errorResult = new JSONObject();
    errorResult.put("error", errorMessage);

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.putAll(corsHeaders);

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(errorResult.toString())
        .withIsBase64Encoded(false);
  }
}