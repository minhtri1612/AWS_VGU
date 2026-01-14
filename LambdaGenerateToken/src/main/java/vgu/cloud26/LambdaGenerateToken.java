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

public class LambdaGenerateToken implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String SECRET_KEY_PARAM_NAME = "keytokenhash"; // Parameter Store name

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

            // Decode base64 if flag is set
            if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded()) {
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(body);
                    body = new String(decodedBytes, StandardCharsets.UTF_8);
                    logger.log("Decoded base64 request body (flag was set)");
                } catch (Exception e) {
                    logger.log("Failed to decode base64 body: " + e.getMessage());
                }
            }

            // Try to parse as JSON, if fails try base64 decode
            JSONObject bodyJSON;
            try {
                bodyJSON = new JSONObject(body);
                // Check if wrapped by API Gateway or Lambda Entry Point
                if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
                    String actualBody = bodyJSON.getString("body");
                    logger.log("Extracted actual body from wrapper");
                    if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
                        bodyJSON = new JSONObject(actualBody);
                    } else {
                        return createErrorResponse(400, "Error: Empty body in wrapper");
                    }
                }
            } catch (Exception e) {
                // If JSON parsing failed, try base64 decoding as fallback
                logger.log("Failed to parse as JSON, trying base64 decode: " + e.getMessage());
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(body);
                    String decodedBody = new String(decodedBytes, StandardCharsets.UTF_8);
                    logger.log("Successfully decoded base64, now parsing JSON");
                    bodyJSON = new JSONObject(decodedBody);
                    // Check if wrapped by orchestrator
                    if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
                        String actualBody = bodyJSON.getString("body");
                        if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
                            bodyJSON = new JSONObject(actualBody);
                        } else {
                            return createErrorResponse(400, "Error: Empty body in wrapper");
                        }
                    }
                } catch (Exception decodeException) {
                    logger.log("Failed to decode base64 or parse JSON: " + decodeException.getMessage());
                    return createErrorResponse(400, "Error: Invalid JSON in request body: " + e.getMessage());
                }
            }

            // Extract action, email and token from request
            String action = bodyJSON.optString("action", "");
            String email = bodyJSON.optString("email", null);
            String providedToken = bodyJSON.optString("token", null);

            // Handle request_token action
            if ("request_token".equals(action)) {
                if (email == null || email.isEmpty()) {
                    return createErrorResponse(400, "Missing email parameter");
                }

                // Get SECRET_KEY from Parameter Store
                String secretKey = getSecretKeyFromParameterStore(logger);
                if (secretKey == null || secretKey.isEmpty()) {
                    return createErrorResponse(500, "Error: Failed to retrieve SECRET_KEY from Parameter Store");
                }

                // Generate token from email using hash function
                String token = generateSecureToken(email, secretKey, logger);
                
                if (token == null) {
                    return createErrorResponse(500, "Error generating token");
                }

                logger.log("Generated token for email: " + email);

                // Return token directly in response (no SES needed)
                JSONObject result = new JSONObject();
                result.put("message", "Token generated successfully");
                result.put("email", email);
                result.put("token", token); // Return token directly

                return createSuccessResponse(result);
            }

            // Handle verify_token action
            if ("verify_token".equals(action)) {
                if (email == null || email.isEmpty() || providedToken == null || providedToken.isEmpty()) {
                    return createErrorResponse(400, "Missing email or token parameter");
                }

                // Get SECRET_KEY from Parameter Store
                String secretKey = getSecretKeyFromParameterStore(logger);
                if (secretKey == null || secretKey.isEmpty()) {
                    return createErrorResponse(500, "Error: Failed to retrieve SECRET_KEY from Parameter Store");
                }

                // Generate token from email and compare
                String generatedToken = generateSecureToken(email, secretKey, logger);
                
                if (generatedToken == null) {
                    return createErrorResponse(500, "Error generating token for comparison");
                }

                boolean isValid = generatedToken.equals(providedToken);
                
                logger.log("Token validation result for email " + email + ": " + isValid);

                JSONObject result = new JSONObject();
                result.put("valid", isValid);
                result.put("email", email);
                result.put("message", isValid ? "Token is valid" : "Token is invalid");

                return createSuccessResponse(result);
            }

            return createErrorResponse(400, "Invalid action. Use 'request_token' or 'verify_token'");

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

    /**
     * Get SECRET_KEY from AWS Systems Manager Parameter Store via Lambda Extension
     * @param logger Lambda logger
     * @return SECRET_KEY value, or null if error
     */
    private String getSecretKeyFromParameterStore(LambdaLogger logger) {
        try {
            // Use Lambda Extension to get parameter from Parameter Store
            HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            // Get session token from environment (provided by Lambda runtime)
            String sessionToken = System.getenv("AWS_SESSION_TOKEN");
            
            HttpRequest requestParameter = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=" + SECRET_KEY_PARAM_NAME + "&withDecryption=true"))
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

}
