package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

public class LambdaGetPhotosDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RDS_INSTANCE_HOSTNAME = System.getenv().getOrDefault("RDS_HOSTNAME", "project1.c986iw6k2ihl.ap-southeast-2.rds.amazonaws.com");
  private static final int RDS_INSTANCE_PORT = Integer.parseInt(System.getenv().getOrDefault("RDS_PORT", "3306"));
  private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "cloud26");
  private static final String DB_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "Cloud26Password123!");
  private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "Cloud26");
  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/" + DB_NAME;

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

      PreparedStatement st = mySQLClient.prepareStatement("SELECT * FROM Photos");
      ResultSet rs = st.executeQuery();

      while (rs.next()) {
        JSONObject item = new JSONObject();
        item.put("ID", rs.getInt("ID"));
        item.put("Description", rs.getString("Description"));
        item.put("S3Key", rs.getString("S3Key"));
        items.put(item);
      }

      rs.close();
      st.close();
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

  private static Properties setMySqlConnectionProperties() {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    mysqlConnectionProperties.setProperty("password", DB_PASSWORD); // Use password instead of IAM token
    return mysqlConnectionProperties;
  }
}
