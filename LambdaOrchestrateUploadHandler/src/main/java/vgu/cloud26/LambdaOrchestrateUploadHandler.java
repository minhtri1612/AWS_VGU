package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;

public class LambdaOrchestrateUploadHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  private final SfnClient sfnClient;
  private final ExecutorService executorService;

  // Get function names from environment variables
  private static final String ADD_PHOTO_DB_FUNC_NAME = System.getenv().getOrDefault("ADD_PHOTO_DB_FUNC_NAME", "LambdaAddPhotoDB");
  private static final String UPLOAD_OBJECTS_FUNC_NAME = System.getenv().getOrDefault("UPLOAD_OBJECTS_FUNC_NAME", "LambdaUploadObjects");
  private static final String RESIZE_WRAPPER_FUNC_NAME = System.getenv().getOrDefault("RESIZE_WRAPPER_FUNC_NAME", "LambdaResizeWrapper");
  private static final String DELETE_OBJECTS_FUNC_NAME = System.getenv().getOrDefault("DELETE_OBJECTS_FUNC_NAME", "LambdaDeleteObjects");
  private static final String STATE_MACHINE_ARN = System.getenv().getOrDefault("STATE_MACHINE_ARN", "");

  public LambdaOrchestrateUploadHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.sfnClient = SfnClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.executorService = Executors.newFixedThreadPool(4); // For up to 4 concurrent activities
  }

  // Helper to call a worker Lambda
  public String callLambda(String functionName, String payload, LambdaLogger logger) {
    try {
      InvokeRequest invokeRequest =
          InvokeRequest.builder()
              .functionName(functionName)
              .payload(SdkBytes.fromUtf8String(payload))
              .invocationType("RequestResponse") // Synchronous
              .build();

      InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
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
      return "Failed: " + e.getMessage();
    }
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
      // Handle null event gracefully
      if (event == null) {
        logger.log("ERROR: Received null event");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withHeaders(corsHeaders)
            .withBody("{\"error\":\"Invalid request: event is null\"}")
            .withIsBase64Encoded(false);
      }
      
      // Handle OPTIONS preflight requests for CORS
      String httpMethod = event.getHttpMethod();
      if (httpMethod != null && "OPTIONS".equalsIgnoreCase(httpMethod)) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withHeaders(corsHeaders)
            .withBody("");
      }

      // Determine operation type based on HTTP method
      httpMethod = httpMethod != null ? httpMethod.toUpperCase() : "POST";
      String userRequestBody = event.getBody();
      logger.log("HTTP Method: " + httpMethod);
      logger.log("Original request body length: " + (userRequestBody != null ? userRequestBody.length() : 0));

      // Handle DELETE operation
      if ("DELETE".equals(httpMethod)) {
        APIGatewayProxyResponseEvent response = handleDeleteOperation(userRequestBody, logger);
        // Ensure CORS headers are present
        if (response.getHeaders() == null) {
          response.setHeaders(new HashMap<>());
        }
        response.getHeaders().putAll(corsHeaders);
        return response;
      }

      // Handle POST operation (Upload workflow)
      APIGatewayProxyResponseEvent response = handleUploadOperation(userRequestBody, logger);
      // Ensure CORS headers are present
      if (response.getHeaders() == null) {
        response.setHeaders(new HashMap<>());
      }
      response.getHeaders().putAll(corsHeaders);
      return response;
      
    } catch (Exception e) {
      logger.log("ERROR in handleRequest: " + e.getMessage());
      e.printStackTrace();
      JSONObject errorResult = new JSONObject();
      errorResult.put("error", "Internal server error: " + e.getMessage());
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withHeaders(corsHeaders)
          .withBody(errorResult.toString())
          .withIsBase64Encoded(false);
    }
  }

  // Handle DELETE workflow: Delete from DB + Delete from S3 (both buckets)
  private APIGatewayProxyResponseEvent handleDeleteOperation(String userRequestBody, LambdaLogger logger) {
    try {
      // Parse request body to get the key
      JSONObject bodyJSON = new JSONObject(userRequestBody != null ? userRequestBody : "{}");
      String key = bodyJSON.optString("key", "");
      
      if (key.isEmpty()) {
        JSONObject errorResult = new JSONObject();
        errorResult.put("error", "Missing 'key' field in request body");
        return createErrorResponse(400, errorResult.toString());
      }

      logger.log("Starting DELETE workflow for key: " + key);

      // Prepare payload for LambdaDeleteObjects (deletes from both S3 buckets)
      JSONObject lambdaEvent = new JSONObject();
      lambdaEvent.put("httpMethod", "DELETE");
      lambdaEvent.put("body", userRequestBody);
      JSONObject eventHeaders = new JSONObject();
      eventHeaders.put("Content-Type", "application/json");
      lambdaEvent.put("headers", eventHeaders);
      String downstreamPayload = lambdaEvent.toString();

      // Execute DELETE activities CONCURRENTLY
      logger.log("Starting concurrent execution of 2 delete activities...");

      // Activity 1: Delete from Database
      CompletableFuture<String> deleteDbFuture = CompletableFuture.supplyAsync(() -> {
        logger.log("Delete Activity 1: DB Delete (started)");
        return deleteFromDatabase(key, logger);
      }, executorService);

      // Activity 2: Delete from S3 (both original and resized buckets)
      CompletableFuture<String> deleteS3Future = CompletableFuture.supplyAsync(() -> {
        logger.log("Delete Activity 2: S3 Delete (started)");
        return callLambda(DELETE_OBJECTS_FUNC_NAME, downstreamPayload, logger);
      }, executorService);

      // Wait for all activities to complete
      CompletableFuture.allOf(deleteDbFuture, deleteS3Future).join();

      JSONObject results = new JSONObject();
      results.put("Activity_1_Database_Delete", deleteDbFuture.get());
      results.put("Activity_2_S3_Delete", deleteS3Future.get());

      logger.log("All delete activities completed");

      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "application/json");
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody(results.toString(4))
          .withIsBase64Encoded(false);

    } catch (Exception e) {
      logger.log("Error in delete orchestrator: " + e.getMessage());
      return createErrorResponse(500, "Delete orchestrator failed: " + e.getMessage());
    }
  }

  // Handle UPLOAD workflow: Use Step Functions instead of direct Lambda calls
  private APIGatewayProxyResponseEvent handleUploadOperation(String userRequestBody, LambdaLogger logger) {
    try {
      if (STATE_MACHINE_ARN == null || STATE_MACHINE_ARN.isEmpty()) {
        logger.log("STATE_MACHINE_ARN not configured, falling back to direct Lambda calls");
        return handleUploadOperationDirect(userRequestBody, logger);
      }

      logger.log("Starting Step Functions execution for upload workflow...");

      // Prepare input for Step Functions
      JSONObject stepInput = new JSONObject();
      stepInput.put("body", userRequestBody != null ? userRequestBody : "{}");

      // Start Step Functions execution
      StartExecutionRequest executionRequest = StartExecutionRequest.builder()
          .stateMachineArn(STATE_MACHINE_ARN)
          .input(stepInput.toString())
          .build();

      StartExecutionResponse executionResponse = sfnClient.startExecution(executionRequest);
      String executionArn = executionResponse.executionArn();
      
      logger.log("Step Functions execution started: " + executionArn);

      // Wait for execution to complete (with timeout)
      String finalStatus = waitForExecution(executionArn, logger, 300); // 5 minute timeout
      
      if ("SUCCEEDED".equals(finalStatus)) {
        // Get execution output
        String output = getExecutionOutput(executionArn, logger);
        
        JSONObject results = new JSONObject();
        results.put("executionArn", executionArn);
        results.put("status", "SUCCEEDED");
        results.put("output", output);
        results.put("message", "Upload workflow completed successfully via Step Functions");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withHeaders(headers)
            .withBody(results.toString(4))
            .withIsBase64Encoded(false);
      } else {
        throw new Exception("Step Functions execution failed with status: " + finalStatus);
      }

    } catch (Exception e) {
      logger.log("Error in Step Functions upload: " + e.getMessage());
      // Fallback to direct Lambda calls if Step Functions fails
      logger.log("Falling back to direct Lambda calls...");
      return handleUploadOperationDirect(userRequestBody, logger);
    }
  }

  // Fallback: Direct Lambda calls (original implementation)
  private APIGatewayProxyResponseEvent handleUploadOperationDirect(String userRequestBody, LambdaLogger logger) {
    try {
      // Create APIGatewayProxyRequestEvent structure for direct Lambda invocation
      JSONObject lambdaEvent = new JSONObject();
      lambdaEvent.put("httpMethod", "POST");
      lambdaEvent.put("body", userRequestBody != null ? userRequestBody : "{}");
      
      // Add headers
      JSONObject eventHeaders = new JSONObject();
      eventHeaders.put("Content-Type", "application/json");
      lambdaEvent.put("headers", eventHeaders);
      
      String downstreamPayload = lambdaEvent.toString();
      logger.log("Downstream payload length: " + downstreamPayload.length());

      // Execute UPLOAD activities CONCURRENTLY
      logger.log("Starting concurrent execution of 3 upload activities (direct Lambda calls)...");

      // Activity 1: Insert Key & Description into RDS
      CompletableFuture<String> activity1Future = CompletableFuture.supplyAsync(() -> {
        logger.log("Activity 1: DB Insert (started)");
        return callLambda(ADD_PHOTO_DB_FUNC_NAME, downstreamPayload, logger);
      }, executorService);

      // Activity 2: Upload Original to S3
      CompletableFuture<String> activity2Future = CompletableFuture.supplyAsync(() -> {
        logger.log("Activity 2: Original Upload (started)");
        return callLambda(UPLOAD_OBJECTS_FUNC_NAME, downstreamPayload, logger);
      }, executorService);

      // Activity 3: Upload Resized to S3
      CompletableFuture<String> activity3Future = CompletableFuture.supplyAsync(() -> {
        logger.log("Activity 3: Resize Upload (started)");
        return callLambda(RESIZE_WRAPPER_FUNC_NAME, downstreamPayload, logger);
      }, executorService);

      // Wait for all activities to complete
      CompletableFuture.allOf(activity1Future, activity2Future, activity3Future).join();
      
      JSONObject results = new JSONObject();
      results.put("Activity_1_Database", activity1Future.get());
      results.put("Activity_2_Original_S3", activity2Future.get());
      results.put("Activity_3_Resize_S3", activity3Future.get());

      logger.log("All upload activities completed");

      // Return Combined Report
      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "application/json");
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody(results.toString(4)) // Pretty print JSON
          .withIsBase64Encoded(false);
    } catch (Exception e) {
      logger.log("Error in direct upload orchestrator: " + e.getMessage());
      return createErrorResponse(500, "Upload orchestrator failed: " + e.getMessage());
    }
  }

  // Wait for Step Functions execution to complete
  private String waitForExecution(String executionArn, LambdaLogger logger, int timeoutSeconds) {
    long startTime = System.currentTimeMillis();
    long timeoutMillis = timeoutSeconds * 1000L;
    
    while (System.currentTimeMillis() - startTime < timeoutMillis) {
      try {
        var describeRequest = DescribeExecutionRequest.builder()
            .executionArn(executionArn)
            .build();
        
        var execution = sfnClient.describeExecution(describeRequest);
        ExecutionStatus status = execution.status();
        
        if (status == ExecutionStatus.SUCCEEDED || 
            status == ExecutionStatus.FAILED || 
            status == ExecutionStatus.TIMED_OUT || 
            status == ExecutionStatus.ABORTED) {
          logger.log("Step Functions execution completed with status: " + status);
          return status.toString();
        }
        
        // Wait 500ms before checking again
        Thread.sleep(500);
      } catch (Exception e) {
        logger.log("Error checking execution status: " + e.getMessage());
        return "UNKNOWN";
      }
    }
    
    logger.log("Step Functions execution timed out after " + timeoutSeconds + " seconds");
    return "TIMED_OUT";
  }

  // Get Step Functions execution output
  private String getExecutionOutput(String executionArn, LambdaLogger logger) {
    try {
      var describeRequest = DescribeExecutionRequest.builder()
          .executionArn(executionArn)
          .build();
      
      var execution = sfnClient.describeExecution(describeRequest);
      return execution.output() != null ? execution.output() : "{}";
    } catch (Exception e) {
      logger.log("Error getting execution output: " + e.getMessage());
      return "{\"error\":\"Could not retrieve output\"}";
    }
  }

  // Delete from Database by S3Key
  private String deleteFromDatabase(String key, LambdaLogger logger) {
    // Configuration - using environment variables (same as LambdaAddPhotoDB)
    String rdsHostname = System.getenv().getOrDefault("RDS_HOSTNAME", "project1.c986iw6k2ihl.ap-southeast-2.rds.amazonaws.com");
    int rdsPort = Integer.parseInt(System.getenv().getOrDefault("RDS_PORT", "3306"));
    String dbUser = System.getenv().getOrDefault("DB_USER", "cloud26");
    String dbPassword = System.getenv().getOrDefault("DB_PASSWORD", "Cloud26Password123!");
    String dbName = System.getenv().getOrDefault("DB_NAME", "Cloud26");
    String jdbcUrl = "jdbc:mysql://" + rdsHostname + ":" + rdsPort + "/" + dbName;

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");
      Properties props = new Properties();
      props.setProperty("useSSL", "true");
      props.setProperty("user", dbUser);
      props.setProperty("password", dbPassword);

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
}
