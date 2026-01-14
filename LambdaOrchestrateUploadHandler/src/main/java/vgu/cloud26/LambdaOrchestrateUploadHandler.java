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
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class LambdaOrchestrateUploadHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  private final SfnClient sfnClient;
  private final SsmClient ssmClient;
  private final ExecutorService executorService;

  // Get function names from environment variables (UPLOAD-ONLY ORCHESTRATOR)
  // This Lambda is responsible **only** for the upload workflow:
  //   key -> insert row (DB) -> upload original -> resize & upload resized
  // All delete logic is handled by LambdaOrchestrateDeleteHandler.
  private static final String ADD_PHOTO_DB_FUNC_NAME = System.getenv()
      .getOrDefault("ADD_PHOTO_DB_FUNC_NAME", "LambdaAddPhotoDB");
  private static final String UPLOAD_OBJECTS_FUNC_NAME = System.getenv()
      .getOrDefault("UPLOAD_OBJECTS_FUNC_NAME", "LambdaUploadObjects");
  private static final String RESIZE_WRAPPER_FUNC_NAME = System.getenv()
      .getOrDefault("RESIZE_WRAPPER_FUNC_NAME", "LambdaResizeWrapper");
  private static final String STATE_MACHINE_ARN = System.getenv().getOrDefault("STATE_MACHINE_ARN", "");

  public LambdaOrchestrateUploadHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.sfnClient = SfnClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.ssmClient = SsmClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.executorService = Executors.newFixedThreadPool(4); // For up to 4 concurrent activities
  }

  // Helper to call a worker Lambda
  public String callLambda(String functionName, String payload, LambdaLogger logger) {
    try {
      InvokeRequest invokeRequest = InvokeRequest.builder()
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

  // Handle UPLOAD workflow: Use Step Functions instead of direct Lambda calls
  private APIGatewayProxyResponseEvent handleUploadOperation(String userRequestBody, LambdaLogger logger) {
    try {
      // Decode base64 if needed
      if (userRequestBody != null && !userRequestBody.startsWith("{")) {
        try {
          byte[] decodedBytes = java.util.Base64.getDecoder().decode(userRequestBody);
          userRequestBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
          logger.log("Decoded base64 request body");
        } catch (Exception e) {
          logger.log("Failed to decode base64: " + e.getMessage());
        }
      }

      // SECURITY: Verify token before allowing upload
      String token = null;
      String email = null;
      try {
        JSONObject bodyJSON = new JSONObject(userRequestBody != null ? userRequestBody : "{}");
        token = bodyJSON.optString("token", null);
        email = bodyJSON.optString("email", null); // Frontend should send email too
      } catch (Exception e) {
        logger.log("Error parsing request body: " + e.getMessage());
      }

      // Verify token using hash (not DB)
      if (token == null || email == null) {
        return createErrorResponse(403, "Missing token or email");
      }

      if (!verifyTokenWithHash(email, token, logger)) {
        return createErrorResponse(403, "Invalid token");
      }
      if (email == null) {
        JSONObject errorResult = new JSONObject();
        errorResult.put("error", "Invalid or expired token");
        return createErrorResponse(403, errorResult.toString());
      }

      logger.log("Token verified, email from token: " + email);

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

  // Fallback: Direct Lambda calls with sequential workflow
  // Workflow: key -> insert row -> {key, bucket, context} -> Upload Object -> catch error -> [Resized Context -> {key, resized content, catch error} -> Upload (key, resized content)]
  private APIGatewayProxyResponseEvent handleUploadOperationDirect(String userRequestBody, LambdaLogger logger) {
    JSONObject results = new JSONObject();
    
    try {
      // Decode base64 if needed
      if (userRequestBody != null && !userRequestBody.startsWith("{")) {
        try {
          byte[] decodedBytes = Base64.getDecoder().decode(userRequestBody);
          userRequestBody = new String(decodedBytes, StandardCharsets.UTF_8);
          logger.log("Decoded base64 request body");
        } catch (Exception e) {
          logger.log("Failed to decode base64: " + e.getMessage());
        }
      }

      // SECURITY: Verify token (already verified in handleUploadOperation, but double-check here)
      String token = null;
      String email = null;
      try {
        JSONObject bodyJSON = new JSONObject(userRequestBody != null ? userRequestBody : "{}");
        token = bodyJSON.optString("token", null);
        email = bodyJSON.optString("email", null);
      } catch (Exception e) {
        logger.log("Error parsing request body: " + e.getMessage());
      }

      if (token == null || email == null) {
        return createErrorResponse(403, "Missing token or email");
      }

      if (!verifyTokenWithHash(email, token, logger)) {
        return createErrorResponse(403, "Invalid token");
      }

      // Parse request to get key and content
      JSONObject bodyJSON = new JSONObject(userRequestBody != null ? userRequestBody : "{}");
      String key = bodyJSON.optString("key", "");
      String content = bodyJSON.optString("content", "");
      
      if (key.isEmpty() || content.isEmpty()) {
        return createErrorResponse(400, "Missing 'key' or 'content' field");
      }

      logger.log("Starting sequential upload workflow for key: " + key);

      // Create APIGatewayProxyRequestEvent structure for Lambda invocation
      JSONObject lambdaEvent = new JSONObject();
      lambdaEvent.put("httpMethod", "POST");
      lambdaEvent.put("body", userRequestBody != null ? userRequestBody : "{}");

      // Add headers
      JSONObject eventHeaders = new JSONObject();
      eventHeaders.put("Content-Type", "application/json");
      lambdaEvent.put("headers", eventHeaders);

      String downstreamPayload = lambdaEvent.toString();

      // STEP 1: Insert Key & Description into RDS
      logger.log("Step 1: Inserting row into database...");
      String dbResult = callLambda(ADD_PHOTO_DB_FUNC_NAME, downstreamPayload, logger);
      results.put("Activity_1_Database", dbResult);
      
      // Check if DB insert failed
      if (dbResult.contains("Error") || dbResult.contains("Failed")) {
        logger.log("Database insert failed, aborting workflow");
        results.put("Activity_2_Original_S3", "Skipped (DB insert failed)");
        results.put("Activity_3_Resize_S3", "Skipped (DB insert failed)");
        return createResultsResponse(500, results);
      }

      // STEP 2: Upload Original to S3
      logger.log("Step 2: Uploading original to S3...");
      String uploadResult = callLambda(UPLOAD_OBJECTS_FUNC_NAME, downstreamPayload, logger);
      results.put("Activity_2_Original_S3", uploadResult);
      
      // Check if upload failed
      if (uploadResult.contains("Error") || uploadResult.contains("Failed")) {
        logger.log("Original upload failed, aborting resize workflow");
        results.put("Activity_3_Resize_S3", "Skipped (Original upload failed)");
        return createResultsResponse(500, results);
      }

      // STEP 3: Resize and Upload Resized to S3 (only if original upload succeeded)
      logger.log("Step 3: Resizing and uploading resized image...");
      String resizeResult = callLambda(RESIZE_WRAPPER_FUNC_NAME, downstreamPayload, logger);
      results.put("Activity_3_Resize_S3", resizeResult);
      
      // Check if resize failed (non-critical, but log it)
      if (resizeResult.contains("Error") || resizeResult.contains("Failed")) {
        logger.log("Resize upload failed (non-critical): " + resizeResult);
      }

      logger.log("All upload activities completed");

      // Return Combined Report
      return createResultsResponse(200, results);

    } catch (Exception e) {
      logger.log("Error in direct upload orchestrator: " + e.getMessage());
      e.printStackTrace();
      results.put("error", "Upload orchestrator failed: " + e.getMessage());
      return createResultsResponse(500, results);
    }
  }

  // Helper to create results response
  private APIGatewayProxyResponseEvent createResultsResponse(int statusCode, JSONObject results) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(results.toString(4)) // Pretty print JSON
        .withIsBase64Encoded(false);
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
   * Get SECRET_KEY from AWS Systems Manager Parameter Store via AWS SDK
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
