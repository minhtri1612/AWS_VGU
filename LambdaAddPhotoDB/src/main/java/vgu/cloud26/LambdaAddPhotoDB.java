package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LambdaAddPhotoDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Configuration - strictly from environment variables
  private static final String RDS_INSTANCE_HOSTNAME = System.getenv("RDS_HOSTNAME");
  private static final String RDS_INSTANCE_PORT_STR = System.getenv("RDS_PORT");
  private static final String DB_USER = System.getenv("DB_USER");
  private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");
  private static final String DB_NAME = System.getenv("DB_NAME");

  // We initialize the URL lazily or in the handler to ensure env vars are checked
  // at runtime
  // but for static initialization, we must ensure they exist or the Lambda init
  // will fail (which is good)
  static {
    if (RDS_INSTANCE_HOSTNAME == null || RDS_INSTANCE_PORT_STR == null ||
        DB_USER == null || DB_PASSWORD == null || DB_NAME == null) {
      throw new RuntimeException(
          "Missing required environment variables: RDS_HOSTNAME, RDS_PORT, DB_USER, DB_PASSWORD, DB_NAME");
    }
  }

  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT_STR + "/"
      + DB_NAME;

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();   

    try {
      // 1. Parse the body with error handling
      String requestBody = event.getBody();

      logger.log("Received body length: " + (requestBody != null ? requestBody.length() : 0));

      if (requestBody == null || requestBody.isEmpty() || requestBody.equals("{}")) {
        return createResponse(400, "Error: Request body is empty");
      }

      // Decode base64 if flag is set
      if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
        try {
          byte[] decodedBytes = java.util.Base64.getDecoder().decode(requestBody);
          requestBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
          logger.log("Decoded base64 request body (flag was set)");
        } catch (Exception e) {
          logger.log("Failed to decode base64 body: " + e.getMessage());
        }
      }

      // Handle wrapped body from EntryPoint or direct Lambda invocation
      JSONObject bodyJSON;
      try {
        bodyJSON = new JSONObject(requestBody);
        // Check if wrapped by EntryPoint or LambdaUploadObjects
        if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
          String actualBody = bodyJSON.getString("body");
          logger.log("Extracted actual body from wrapper");
          if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
            bodyJSON = new JSONObject(actualBody);
          } else {
            return createResponse(400, "Error: Empty body in wrapper");
          }
        }
      } catch (Exception e) {
        // If JSON parsing failed, try base64 decoding as fallback (even if flag wasn't
        // set)
        logger.log("Failed to parse as JSON, trying base64 decode: " + e.getMessage());
        try {
          byte[] decodedBytes = java.util.Base64.getDecoder().decode(requestBody);
          String decodedBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
          logger.log("Successfully decoded base64, now parsing JSON");
          bodyJSON = new JSONObject(decodedBody);
          // Check if wrapped by orchestrator
          if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
            String actualBody = bodyJSON.getString("body");
            if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
              bodyJSON = new JSONObject(actualBody);
            } else {
              return createResponse(400, "Error: Empty body in wrapper");
            }
          }
        } catch (Exception decodeException) {
          logger.log("Failed to decode base64 or parse JSON: " + decodeException.getMessage());
          return createResponse(400, "Error: Invalid JSON in request body: " + e.getMessage());
        }
      }

      // Extract required fields
      String originalFileName = bodyJSON.optString("key", "");
      String description = bodyJSON.optString("description", "Uploaded photo");
      String token = bodyJSON.optString("token", ""); // Get token from client
      String email = bodyJSON.optString("email", null); // Get email from orchestrator

      if (originalFileName.isEmpty()) {
        return createResponse(400, "Error: Missing 'key' field (filename)");
      }

      // SECURITY: Don't trust client - verify token using hash (not DB)
      if (token == null || token.isEmpty() || email == null || email.isEmpty()) {
        return createResponse(403, "Error: Token and email required");
      }

      if (!verifyTokenWithHash(email, token, logger)) {
        return createResponse(403, "Error: Invalid token");
      }

      logger.log("Processing DB insert for file: " + originalFileName + ", email from token: " + email);

      Class.forName("com.mysql.cj.jdbc.Driver");

      // 2. Connect to MySQL server (without database name) to create database if
      // needed
      String serverUrl = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT_STR;
      Properties props = setMySqlConnectionProperties();

      try (Connection serverConnection = DriverManager.getConnection(serverUrl, props)) {
        // Create database if it doesn't exist
        try (java.sql.Statement stmt = serverConnection.createStatement()) {
          stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB_NAME);
          logger.log("Database " + DB_NAME + " created or already exists");
        }
      }

      // 3. Connect to the specific database and create table if needed
      try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {
        // Create table if it doesn't exist (with Email column)
        try (java.sql.Statement stmt = mySQLClient.createStatement()) {
          String createTableSql = "CREATE TABLE IF NOT EXISTS Photos (" +
              "ID INT AUTO_INCREMENT PRIMARY KEY, " +
              "Description VARCHAR(255), " +
              "S3Key VARCHAR(255), " +
              "Email VARCHAR(255)" +
              ")";
          stmt.executeUpdate(createTableSql);
          logger.log("Table Photos created or already exists");
          
          // Add Email column if it doesn't exist (for existing tables)
          try {
            stmt.executeUpdate("ALTER TABLE Photos ADD COLUMN Email VARCHAR(255)");
            logger.log("Added Email column to Photos table");
          } catch (Exception e) {
            // Column already exists, ignore
            logger.log("Email column already exists or error: " + e.getMessage());
          }
        }

        // Email already verified and extracted from token above

        // 4. Insert the photo record
        String sql = "INSERT INTO Photos (Description, S3Key, Email) VALUES (?, ?, ?)";
        try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
          st.setString(1, description); // User's Description
          st.setString(2, originalFileName); // ACTUAL filename (not hash!)
          st.setString(3, email != null && !email.isEmpty() ? email : null); // User's Email (can be null for old uploads)
          st.executeUpdate();
          logger.log("Inserted row: " + description + " | " + originalFileName + " | Email: " + (email != null && !email.isEmpty() ? email : "NULL"));
        }
      }

      // 3. Return JSON Success with CORS headers
      return createResponse(200, "Success: Photo added to database");

    } catch (ClassNotFoundException ex) {
      logger.log("MySQL Driver not found: " + ex.toString());
      return createResponse(500, "Error: Database driver not found");
    } catch (Exception ex) {
      logger.log("Error: " + ex.toString());
      return createResponse(500, "Error adding to DB: " + ex.getMessage());
    }
  }

  // Helper to create standardized JSON response with CORS
  private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
    java.util.Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

    JSONObject responseBody = new JSONObject();
    responseBody.put("message", message);

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(responseBody.toString())
        .withHeaders(headers)
        .withIsBase64Encoded(false);
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
      HttpClient client = HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

      String sessionToken = System.getenv("AWS_SESSION_TOKEN");
      
      HttpRequest requestParameter = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=keytokenhash&withDecryption=true"))
          .header("Accept", "application/json")
          .header("X-Aws-Parameters-Secrets-Token", sessionToken != null ? sessionToken : "")
          .GET()
          .build();

      HttpResponse<String> responseParameter = client.send(requestParameter, HttpResponse.BodyHandlers.ofString());

      if (responseParameter.statusCode() != 200) {
        logger.log("Failed to get parameter from Parameter Store. Status: " + responseParameter.statusCode());
        return null;
      }

      String jsonResponse = responseParameter.body();
      JSONObject jsonBody = new JSONObject(jsonResponse);
      JSONObject parameter = jsonBody.getJSONObject("Parameter");
      String secretKey = parameter.getString("Value");

      logger.log("Successfully retrieved SECRET_KEY from Parameter Store");
      return secretKey;

    } catch (Exception e) {
      logger.log("Error retrieving SECRET_KEY from Parameter Store: " + e.getMessage());
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
