package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaOrchestrateUploadHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  private final ExecutorService executorService;

  // Get function names from environment variables
  private static final String ADD_PHOTO_DB_FUNC_NAME = System.getenv().getOrDefault("ADD_PHOTO_DB_FUNC_NAME", "LambdaAddPhotoDB");
  private static final String UPLOAD_OBJECTS_FUNC_NAME = System.getenv().getOrDefault("UPLOAD_OBJECTS_FUNC_NAME", "LambdaUploadObjects");
  private static final String RESIZE_WRAPPER_FUNC_NAME = System.getenv().getOrDefault("RESIZE_WRAPPER_FUNC_NAME", "LambdaResizeWrapper");

  public LambdaOrchestrateUploadHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    this.executorService = Executors.newFixedThreadPool(3); // For 3 concurrent activities
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
    
    // Handle OPTIONS preflight requests for CORS
    if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
      Map<String, String> headers = new HashMap<>();
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
      headers.put("Access-Control-Max-Age", "3600");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody("");
    }

    // 1. Prepare Payload
    // When invoking Lambdas directly (not via Function URL), we need to wrap the payload
    // in an APIGatewayProxyRequestEvent structure because the handlers expect that format
    String userRequestBody = event.getBody();
    logger.log("Original request body length: " + (userRequestBody != null ? userRequestBody.length() : 0));
    
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

    // 2. Execute Activities CONCURRENTLY
    logger.log("Starting concurrent execution of 3 activities...");

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
    try {
      CompletableFuture.allOf(activity1Future, activity2Future, activity3Future).join();
      
      JSONObject results = new JSONObject();
      results.put("Activity_1_Database", activity1Future.get());
      results.put("Activity_2_Original_S3", activity2Future.get());
      results.put("Activity_3_Resize_S3", activity3Future.get());

      logger.log("All activities completed");

      // 3. Return Combined Report
      // Note: CORS headers are handled by Function URL configuration
      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "application/json");

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody(results.toString(4)) // Pretty print JSON
          .withIsBase64Encoded(false);
    } catch (Exception e) {
      logger.log("Error in orchestrator: " + e.getMessage());
      JSONObject errorResult = new JSONObject();
      errorResult.put("error", "Orchestrator failed: " + e.getMessage());
      
      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "application/json");
      
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withHeaders(headers)
          .withBody(errorResult.toString())
          .withIsBase64Encoded(false);
    }
  }
}
