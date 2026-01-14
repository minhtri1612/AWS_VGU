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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class LambdaOrchestrateDeleteHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  private final S3Client s3Client;
  private final SsmClient ssmClient;
  private final ExecutorService executorService;

  // Configuration from environment variables
  private static final String SOURCE_BUCKET_NAME = System.getenv("BUCKET_NAME");
  private static final String RESIZED_BUCKET_NAME = System.getenv("RESIZED_BUCKET_NAME");
  private static final String RDS_HOSTNAME = System.getenv("RDS_HOSTNAME");
  private static final String RDS_PORT = System.getenv("RDS_PORT");
  private static final String DB_USER = System.getenv("DB_USER");
  private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");
  private static final String DB_NAME = System.getenv("DB_NAME");

  static {
    if (SOURCE_BUCKET_NAME == null || RESIZED_BUCKET_NAME == null) {
      throw new RuntimeException("Missing required environment variables: BUCKET_NAME, RESIZED_BUCKET_NAME");
    }
    if (RDS_HOSTNAME == null || RDS_PORT == null || DB_USER == null || DB_PASSWORD == null || DB_NAME == null) {
      throw new RuntimeException("Missing required environment variables: RDS_HOSTNAME, RDS_PORT, DB_USER, DB_PASSWORD, DB_NAME");
    }
  }

  public LambdaOrchestrateDeleteHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();
    this.ssmClient = SsmClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.executorService = Executors.newFixedThreadPool(3); // For 3 parallel delete operations
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();

    // Helper to add CORS headers to any response
    Map<String, String> corsHeaders = new HashMap<>();
    corsHeaders.put("Access-Control-Allow-Origin", "*");
    corsHeaders.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    corsHeaders.put("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Amz-Date, X-Api-Key");
    corsHeaders.put("Access-Control-Max-Age", "3600");

    try {
      // Handle OPTIONS preflight requests for CORS
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
        // Try base64 decode as fallback if JSON parsing fails
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

      // Parse request body to get the key
      JSONObject bodyJSON;
      try {
        bodyJSON = new JSONObject(userRequestBody != null ? userRequestBody : "{}");
        // Handle wrapped body
        if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
          String actualBody = bodyJSON.getString("body");
          if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
            bodyJSON = new JSONObject(actualBody);
          }
        }
      } catch (Exception e) {
        logger.log("Error parsing request body: " + e.getMessage());
        return createErrorResponse(400, "Invalid JSON in request body: " + e.getMessage());
      }

      String key = bodyJSON.optString("key", "");

      if (key.isEmpty()) {
        JSONObject errorResult = new JSONObject();
        errorResult.put("error", "Missing 'key' field in request body");
        return createErrorResponse(400, errorResult.toString());
      }

      // SECURITY: Verify token using hash (not DB) and ownership before deletion
      String token = bodyJSON.optString("token", null);
      String email = bodyJSON.optString("email", null); // Frontend should send email too
      
      if (token == null || token.isEmpty() || email == null || email.isEmpty()) {
        return createErrorResponse(403, "Missing token or email");
      }

      if (!verifyTokenWithHash(email, token, logger)) {
        return createErrorResponse(403, "Invalid token");
      }

      // Verify ownership
      if (!verifyPhotoOwnership(key, email, logger)) {
        return createErrorResponse(403, "You don't have permission to delete this photo");
      }

      logger.log("Starting parallel DELETE workflow for key: " + key + ", email: " + email);

      // Execute DELETE activities in PARALLEL
      logger.log("Starting parallel execution of 3 delete activities...");

      // Activity 1: Delete from Original Bucket
      CompletableFuture<String> deleteOriginalFuture = CompletableFuture.supplyAsync(() -> {
        logger.log("Delete Activity 1: Original Bucket Delete (started)");
        return deleteFromOriginalBucket(key, logger);
      }, executorService);

      // Activity 2: Delete from Database
      CompletableFuture<String> deleteDbFuture = CompletableFuture.supplyAsync(() -> {
        logger.log("Delete Activity 2: DB Delete (started)");
        return deleteFromDatabase(key, logger);
      }, executorService);

      // Activity 3: Delete from Resized Bucket
      CompletableFuture<String> deleteResizedFuture = CompletableFuture.supplyAsync(() -> {
        logger.log("Delete Activity 3: Resized Bucket Delete (started)");
        return deleteFromResizedBucket(key, logger);
      }, executorService);

      // Wait for all activities to complete
      CompletableFuture.allOf(deleteOriginalFuture, deleteDbFuture, deleteResizedFuture).join();

      JSONObject results = new JSONObject();
      results.put("Activity_1_Original_Bucket", deleteOriginalFuture.get());
      results.put("Activity_2_Database", deleteDbFuture.get());
      results.put("Activity_3_Resized_Bucket", deleteResizedFuture.get());

      logger.log("All delete activities completed");

      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "application/json");
      headers.putAll(corsHeaders);

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody(results.toString(4))
          .withIsBase64Encoded(false);

    } catch (Exception e) {
      logger.log("Error in delete orchestrator: " + e.getMessage());
      e.printStackTrace();
      return createErrorResponse(500, "Delete orchestrator failed: " + e.getMessage());
    }
  }

  // Delete from Original Bucket
  private String deleteFromOriginalBucket(String key, LambdaLogger logger) {
    try {
      DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
          .bucket(SOURCE_BUCKET_NAME)
          .key(key)
          .build();

      s3Client.deleteObject(deleteRequest);
      logger.log("Successfully deleted from original bucket: " + key);
      
      JSONObject response = new JSONObject();
      response.put("message", "Success: Deleted from original bucket");
      response.put("key", key);
      return response.toString();
    } catch (Exception e) {
      logger.log("Error deleting from original bucket: " + e.getMessage());
      JSONObject errorResponse = new JSONObject();
      errorResponse.put("error", "Original bucket delete failed: " + e.getMessage());
      return errorResponse.toString();
    }
  }

  // Delete from Resized Bucket
  private String deleteFromResizedBucket(String key, LambdaLogger logger) {
    try {
      String resizedKey = "resized-" + key;
      DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
          .bucket(RESIZED_BUCKET_NAME)
          .key(resizedKey)
          .build();

      s3Client.deleteObject(deleteRequest);
      logger.log("Successfully deleted from resized bucket: " + resizedKey);
      
      JSONObject response = new JSONObject();
      response.put("message", "Success: Deleted from resized bucket");
      response.put("key", resizedKey);
      return response.toString();
    } catch (Exception e) {
      logger.log("Error deleting from resized bucket (may not exist): " + e.getMessage());
      JSONObject errorResponse = new JSONObject();
      errorResponse.put("error", "Resized bucket delete failed (may not exist): " + e.getMessage());
      return errorResponse.toString();
    }
  }

  // Delete from Database by S3Key
  private String deleteFromDatabase(String key, LambdaLogger logger) {
    String jdbcUrl = "jdbc:mysql://" + RDS_HOSTNAME + ":" + RDS_PORT + "/" + DB_NAME;

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");
      Properties props = new Properties();
      props.setProperty("useSSL", "true");
      props.setProperty("user", DB_USER);
      props.setProperty("password", DB_PASSWORD);

      try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
        String sql = "DELETE FROM Photos WHERE S3Key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
          stmt.setString(1, key);
          int rowsDeleted = stmt.executeUpdate();

          if (rowsDeleted > 0) {
            logger.log("Successfully deleted " + rowsDeleted + " row(s) from database for key: " + key);
            JSONObject response = new JSONObject();
            response.put("message", "Success: Photo deleted from database");
            response.put("rowsDeleted", rowsDeleted);
            return response.toString();
          } else {
            logger.log("No rows found to delete for key: " + key);
            JSONObject response = new JSONObject();
            response.put("message", "No photo found in database with key: " + key);
            response.put("rowsDeleted", 0);
            return response.toString();
          }
        }
      }
    } catch (Exception e) {
      logger.log("Error deleting from database: " + e.getMessage());
      JSONObject errorResponse = new JSONObject();
      errorResponse.put("error", "Database delete failed: " + e.getMessage());
      return errorResponse.toString();
    }
  }

  // SECURITY: Verify token using hash function (not DB - token is generated from email)
  private boolean verifyTokenWithHash(String email, String token, LambdaLogger logger) {
    if (email == null || email.isEmpty() || token == null || token.isEmpty()) {
      logger.log("Missing email or token");
      return false;
    }

    try {
      // Get SECRET_KEY from Parameter Store
      String secretKey = getSecretKeyFromParameterStore(logger);
      if (secretKey == null || secretKey.isEmpty()) {
        logger.log("Failed to get SECRET_KEY from Parameter Store");
        return false;
      }

      // Regenerate token from email and compare
      String generatedToken = generateSecureToken(email, secretKey, logger);
      
      if (generatedToken == null) {
        logger.log("Error generating token for comparison");
        return false;
      }

      boolean isValid = generatedToken.equals(token);
      logger.log("Token verification result for email " + email + ": " + isValid);
      
      return isValid;

    } catch (Exception e) {
      logger.log("Error verifying token: " + e.getMessage());
      return false;
    }
  }

  // Generate secure token from email using HMAC-SHA256 (same as LambdaGenerateToken)
  private String generateSecureToken(String email, String secretKey, LambdaLogger logger) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec = new SecretKeySpec(
          secretKey.getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"
      );
      mac.init(secretKeySpec);
      byte[] hmacBytes = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));
      String base64 = Base64.getEncoder().encodeToString(hmacBytes);
      return base64;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      logger.log("Error generating token: " + e.getMessage());
      return null;
    }
  }

  // SECURITY: Verify that photo belongs to the authenticated user
  private boolean verifyPhotoOwnership(String key, String email, LambdaLogger logger) {
    try {
      String jdbcUrl = "jdbc:mysql://" + RDS_HOSTNAME + ":" + RDS_PORT + "/" + DB_NAME;
      Properties props = new Properties();
      props.setProperty("useSSL", "true");
      props.setProperty("user", DB_USER);
      props.setProperty("password", DB_PASSWORD);

      Class.forName("com.mysql.cj.jdbc.Driver");
      try (Connection connection = DriverManager.getConnection(jdbcUrl, props)) {
        String sql = "SELECT COUNT(*) as count FROM Photos WHERE S3Key = ? AND Email = ?";
        try (PreparedStatement st = connection.prepareStatement(sql)) {
          st.setString(1, key);
          st.setString(2, email);
          try (java.sql.ResultSet rs = st.executeQuery()) {
            if (rs.next() && rs.getInt("count") > 0) {
              return true;
            } else {
              logger.log("Photo " + key + " does not belong to " + email);
              return false;
            }
          }
        }
      }
    } catch (Exception e) {
      logger.log("Error verifying ownership: " + e.getMessage());
      return false;
    }
  }

  // Helper method to create error responses
  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String errorMessage) {
    JSONObject errorResult = new JSONObject();
    errorResult.put("error", errorMessage);

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(errorResult.toString())
        .withIsBase64Encoded(false);
  }

  /**
   * Get SECRET_KEY from AWS Systems Manager Parameter Store via Lambda Extension
   * @param logger Lambda logger
   * @return SECRET_KEY value, or null if error
   */
  private String getSecretKeyFromParameterStore(LambdaLogger logger) {
    try {
      // Use AWS SDK to get parameter from Parameter Store
      GetParameterRequest parameterRequest = GetParameterRequest.builder()
              .name("keytokenhash")
              .withDecryption(true) // Decrypt SecureString
              .build();

      GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
      String secretKey = parameterResponse.parameter().value();

      logger.log("Successfully retrieved SECRET_KEY from Parameter Store");
      return secretKey;

    } catch (SsmException e) {
      logger.log("Error retrieving SECRET_KEY from Parameter Store: " + e.getMessage());
      // Fallback to env var if Parameter Store fails
      String fallbackKey = System.getenv("SECRET_KEY");
      if (fallbackKey != null && !fallbackKey.isEmpty()) {
        logger.log("Using SECRET_KEY from environment variable as fallback");
        return fallbackKey;
      }
      return null;
    } catch (Exception e) {
      logger.log("Unexpected error retrieving SECRET_KEY: " + e.getMessage());
      // Fallback to env var if Parameter Store fails
      String fallbackKey = System.getenv("SECRET_KEY");
      if (fallbackKey != null && !fallbackKey.isEmpty()) {
        logger.log("Using SECRET_KEY from environment variable as fallback");
        return fallbackKey;
      }
      return null;
    }
  }
}



