package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class LambdaGetPhotosDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Configuration - strictly from environment variables
  private static final String RDS_INSTANCE_HOSTNAME = System.getenv("RDS_HOSTNAME");
  private static final String RDS_INSTANCE_PORT_STR = System.getenv("RDS_PORT");
  private static final String DB_USER = System.getenv("DB_USER");
  private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");
  private static final String DB_NAME = System.getenv("DB_NAME");

  static {
    if (RDS_INSTANCE_HOSTNAME == null || RDS_INSTANCE_PORT_STR == null ||
        DB_USER == null || DB_PASSWORD == null || DB_NAME == null) {
      throw new RuntimeException(
          "Missing required environment variables: RDS_HOSTNAME, RDS_PORT, DB_USER, DB_PASSWORD, DB_NAME");
    }
  }

  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT_STR + "/"
      + DB_NAME;

  // Static SSM Client for Parameter Store access
  private static final SsmClient ssmClient = SsmClient.builder()
          .region(Region.AP_SOUTHEAST_2)
          .build();

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {

    LambdaLogger logger = context.getLogger();

    JSONArray items = new JSONArray();
    int statusCode = 200;
    String errorMessage = null;

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");

      Connection mySQLClient = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

      // Decode base64 if needed
      String requestBody = request.getBody();
      if (requestBody != null && !requestBody.isEmpty() && !requestBody.equals("{}")) {
        if (request.getIsBase64Encoded() != null && request.getIsBase64Encoded()) {
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
            requestBody = new String(decodedBytes, StandardCharsets.UTF_8);
            logger.log("Decoded base64 request body");
          } catch (Exception e) {
            logger.log("Failed to decode base64: " + e.getMessage());
          }
        }
        // Try base64 decode as fallback if JSON parsing fails
        if (!requestBody.startsWith("{")) {
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
            requestBody = new String(decodedBytes, StandardCharsets.UTF_8);
            logger.log("Decoded base64 request body (fallback)");
          } catch (Exception e) {
            logger.log("Failed to decode base64: " + e.getMessage());
          }
        }
      }

      // SECURITY: Don't trust the client - verify token and get email from token
      String token = null;
      String email = null;
      try {
        if (requestBody != null && !requestBody.isEmpty() && !requestBody.equals("{}")) {
          // Handle wrapped body
          JSONObject bodyJSON = new JSONObject(requestBody);
          if (bodyJSON.has("body")) {
            bodyJSON = new JSONObject(bodyJSON.getString("body"));
          }
          token = bodyJSON.optString("token", null);
          email = bodyJSON.optString("email", null); // Frontend should send email too
        }
      } catch (Exception e) {
        logger.log("Error parsing request body: " + e.getMessage());
      }

      // Verify token using hash (not DB)
      if (token == null || email == null) {
        logger.log("Missing token or email - returning empty list");
        items = new JSONArray();
      } else if (!verifyTokenWithHash(email, token, logger)) {
        logger.log("Invalid token - returning empty list");
        items = new JSONArray();
      } else {
        // SECURITY: Return ALL photos (not filtered by email) so users can see all photos
        // But only owner can delete (checked in LambdaOrchestrateDeleteHandler)
        PreparedStatement st = mySQLClient.prepareStatement("SELECT * FROM Photos");
      
        ResultSet rs = st.executeQuery();

        while (rs.next()) {
          JSONObject item = new JSONObject();
          item.put("ID", rs.getInt("ID"));
          item.put("Description", rs.getString("Description"));
          item.put("S3Key", rs.getString("S3Key"));
          String photoEmail = rs.getString("Email");
          if (photoEmail != null) {
            item.put("Email", photoEmail);
          }
          items.put(item);
        }

        rs.close();
        st.close();
      }
      mySQLClient.close();

    } catch (ClassNotFoundException ex) {
      logger.log("MySQL Driver not found: " + ex.toString());
      statusCode = 500;
      errorMessage = "Database driver error";
    } catch (Exception ex) {
      logger.log("Database error: " + ex.toString());
      statusCode = 500;
      errorMessage = "Database connection error: " + ex.getMessage();
    }

    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
    response.setStatusCode(statusCode);

    // Return JSON (not Base64 encoded)
    if (errorMessage != null) {
      JSONObject error = new JSONObject();
      error.put("error", errorMessage);
      response.setBody(error.toString());
    } else {
      response.setBody(items.toString());
    }

    // Add CORS headers
    java.util.Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    response.setHeaders(headers);
    response.setIsBase64Encoded(false);

    return response;
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

  private static Properties setMySqlConnectionProperties() {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    mysqlConnectionProperties.setProperty("password", DB_PASSWORD); // Use password instead of IAM token
    return mysqlConnectionProperties;
  }
}
