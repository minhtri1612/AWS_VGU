package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class LambdaTokenChecker implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Get SECRET_KEY from SSM Parameter Store instead of env var
    private String getSecretKeyFromParameterStore(LambdaLogger logger) {
        try {
            // 1. Build HTTP client
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            // 2. Get session token for authentication
            String sessionToken = System.getenv("AWS_SESSION_TOKEN");

            // 3. Create request to SSM Parameter Store Extension
            HttpRequest requestParameter = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=keytokenhash&withDecryption=true"))
                    .header("Accept", "application/json")
                    .header("X-Aws-Parameters-Secrets-Token", sessionToken != null ? sessionToken : "")
                    .GET()
                    .build();

            // 4. Send request and get response
            HttpResponse<String> responseParameter = client.send(requestParameter, HttpResponse.BodyHandlers.ofString());

            // 5. Parse response
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
            throw new RuntimeException("Failed to get SECRET_KEY from Parameter Store and no fallback available", e);
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        LambdaLogger logger = context.getLogger();

        // Handle OPTIONS preflight requests
        if ("OPTIONS".equalsIgnoreCase(input.getHttpMethod())) {
            return createCorsResponse();
        }

        try {
            String body = input.getBody();
            
            if (body == null || body.isEmpty()) {
                return createErrorResponse(400, "Missing request body");
            }

            // Handle wrapped body from API Gateway or Lambda Entry Point
            JSONObject bodyJSON = new JSONObject(body);
            if (bodyJSON.has("body")) {
                bodyJSON = new JSONObject(bodyJSON.getString("body"));
            }

            // Extract email and token from request
            String email = bodyJSON.optString("email", null);
            String providedToken = bodyJSON.optString("token", null);

            // Validate required parameters
            if (email == null || email.isEmpty() || providedToken == null || providedToken.isEmpty()) {
                logger.log("Error: 'email' and 'token' parameters are required");
                return createErrorResponse(400, "Missing required parameters: email and token");
            }

            // Get SECRET_KEY from Parameter Store
            String secretKey = getSecretKeyFromParameterStore(logger);
            
            // Generate token from email using hash function
            String generatedToken = generateSecureToken(email, secretKey, logger);
            
            if (generatedToken == null) {
                logger.log("Error generating token for comparison");
                return createErrorResponse(500, "Error generating token for comparison");
            }

            // Compare generated token with provided token
            boolean isValid = generatedToken.equals(providedToken);
            
            logger.log("Token validation result for email " + email + ": " + isValid);

            // Return validation result
            JSONObject result = new JSONObject();
            result.put("valid", isValid);
            result.put("email", email);
            result.put("message", isValid ? "Token is valid" : "Token is invalid");

            return createSuccessResponse(result);

        } catch (Exception e) {
            logger.log("Error processing request: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(500, "Invalid JSON input: " + e.getMessage());
        }
    }

    /**
     * Generate secure token from email using HMAC-SHA256
     * @param email The email address to generate token from
     * @param secretKey The secret key for HMAC
     * @param logger Lambda logger
     * @return Base64 encoded token, or null if error
     */
    public static String generateSecureToken(String email, String secretKey, LambdaLogger logger) {
        try {
            // Specify the HMAC-SHA256 algorithm
            Mac mac = Mac.getInstance("HmacSHA256");

            // Create a SecretKeySpec from the provided key
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            );
            
            // Initialize the Mac with the secret key
            mac.init(secretKeySpec);

            // Compute the HMAC-SHA256 hash of the email
            byte[] hmacBytes = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));

            // Convert the byte array to a base64 string
            String base64 = Base64.getEncoder().encodeToString(hmacBytes);

            logger.log("Input Email: " + email);
            logger.log("Generated Token: " + base64);

            return base64;

        } catch (NoSuchAlgorithmException e) {
            logger.log("HmacSHA256 algorithm not found: " + e.getMessage());
            return null;
        } catch (InvalidKeyException ex) {
            logger.log("InvalidKeyException: " + ex.getMessage());
            return null;
        }
    }

    private APIGatewayProxyResponseEvent createSuccessResponse(JSONObject result) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(result.toString());
        response.setIsBase64Encoded(false);

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeaders(headers);

        return response;
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        JSONObject error = new JSONObject();
        error.put("error", message);
        error.put("valid", false);

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(error.toString());
        response.setIsBase64Encoded(false);

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeaders(headers);

        return response;
    }

    private APIGatewayProxyResponseEvent createCorsResponse() {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody("");
        response.setIsBase64Encoded(false);

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
        headers.put("Access-Control-Max-Age", "3600");
        response.setHeaders(headers);

        return response;
    }
}




