package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONObject;

public class LambdaAddPhotoDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Configuration - using environment variables
  private static final String RDS_INSTANCE_HOSTNAME = System.getenv().getOrDefault("RDS_HOSTNAME", "project1.c986iw6k2ihl.ap-southeast-2.rds.amazonaws.com");
  private static final int RDS_INSTANCE_PORT = Integer.parseInt(System.getenv().getOrDefault("RDS_PORT", "3306"));
  private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "cloud26");
  private static final String DB_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "Cloud26Password123!");
  private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "Cloud26");
  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/" + DB_NAME;

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();

    try {
      // 1. Parse the body with error handling
      String requestBody = event.getBody();
      
      logger.log("Received body: " + requestBody);
      
      if (requestBody == null || requestBody.isEmpty() || requestBody.equals("{}")) {
        return createResponse(400, "Error: Request body is empty");
      }

      // Handle wrapped body from EntryPoint or direct Lambda invocation
      JSONObject bodyJSON;
      try {
        bodyJSON = new JSONObject(requestBody);
        // Check if wrapped by EntryPoint or LambdaUploadObjects
        if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
          String actualBody = bodyJSON.getString("body");
          logger.log("Extracted actual body from wrapper: " + actualBody);
          if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
            bodyJSON = new JSONObject(actualBody);
          } else {
            return createResponse(400, "Error: Empty body in wrapper");
          }
        }
      } catch (Exception e) {
        logger.log("Failed to parse body: " + e.getMessage() + ", body was: " + requestBody);
        return createResponse(400, "Error: Invalid JSON in request body: " + e.getMessage());
      }

      // Extract required fields
      String originalFileName = bodyJSON.optString("key", "");
      String description = bodyJSON.optString("description", "Uploaded photo");

      if (originalFileName.isEmpty()) {
        return createResponse(400, "Error: Missing 'key' field (filename)");
      }

      logger.log("Processing DB insert for file: " + originalFileName);

      Class.forName("com.mysql.cj.jdbc.Driver");

      // 2. Connect and Insert (using ACTUAL filename, not hash)
      Properties props = setMySqlConnectionProperties();
      try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {
        String sql = "INSERT INTO Photos (Description, S3Key) VALUES (?, ?)";
        try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
          st.setString(1, description); // User's Description
          st.setString(2, originalFileName); // ACTUAL filename (not hash!)
          st.executeUpdate();
          logger.log("Inserted row: " + description + " | " + originalFileName);
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

  private static Properties setMySqlConnectionProperties() {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    mysqlConnectionProperties.setProperty("password", DB_PASSWORD); // Use password instead of IAM token
    return mysqlConnectionProperties;
  }
}
