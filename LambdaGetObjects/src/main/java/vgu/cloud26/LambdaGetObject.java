package vgu.cloud26;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

public class LambdaGetObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // 1. OPTIMIZATION: Static Clients
    private static final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();
    
    private static final LambdaClient lambdaClient = LambdaClient.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();
    
    private static final SsmClient ssmClient = SsmClient.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    // 2. CONFIGURATION: Environment Variables
    private static final String BUCKET_NAME = System.getenv("BUCKET_NAME");
    private static final String TOKEN_CHECKER_FUNC_NAME = System.getenv().getOrDefault("TOKEN_CHECKER_FUNC_NAME", "LambdaTokenChecker");

    static {
        if (BUCKET_NAME == null) {
            throw new RuntimeException("Missing required environment variable: BUCKET_NAME");
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        // Note: OPTIONS preflight is handled by Function URL CORS configuration
        String requestBody = request.getBody();

        context.getLogger().log("Raw request body: " + requestBody);

        // Case-insensitive header lookup
        String acceptHeader = null;
        String contentTypeHeader = null;
        if (request.getHeaders() != null) {
            for (java.util.Map.Entry<String, String> h : request.getHeaders().entrySet()) {
                if (h.getKey() == null)
                    continue;
                String key = h.getKey();
                if (key.equalsIgnoreCase("Accept")) {
                    acceptHeader = h.getValue();
                } else if (key.equalsIgnoreCase("Content-Type")) {
                    contentTypeHeader = h.getValue();
                }
            }
        }

        context.getLogger().log("Accept header: " + acceptHeader);
        context.getLogger().log("Content-Type header: " + contentTypeHeader);

        // Check query parameters for explicit format request
        String formatParam = null;
        if (request.getQueryStringParameters() != null) {
            formatParam = request.getQueryStringParameters().get("format");
        }

        context.getLogger().log("Format parameter: " + formatParam);

        // If no body or empty body, choose between list and index based on query param
        // or headers
        if (requestBody == null || requestBody.trim().isEmpty() || requestBody.equals("{}")) {
            // Check for explicit format parameter first
            if ("json".equals(formatParam)) {
                context.getLogger().log("format=json parameter detected, returning list of objects");
                return listObjects(context);
            }
            // JavaScript fetch with Content-Type: application/json should get JSON response
            else if (contentTypeHeader != null && contentTypeHeader.toLowerCase().contains("application/json")) {
                context.getLogger().log("Content-Type: application/json detected, returning list of objects");
                return listObjects(context);
            }
            // Browser request with Accept: text/html should get HTML
            else if (acceptHeader != null && acceptHeader.toLowerCase().contains("text/html")) {
                context.getLogger().log("Accept: text/html detected, returning index.html");
                return getSpecificObject("index.html", context);
            }
            // Default: if no clear indication, return HTML for browser compatibility
            else {
                context.getLogger().log("No clear indication, defaulting to index.html for browser");
                return getSpecificObject("index.html", context);
            }
        }

        // Check if the body is base64 encoded (API Gateway does this with
        // binary_media_types)
        if (requestBody != null && !requestBody.startsWith("{")) {
            try {
                // Decode base64
                byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
                requestBody = new String(decodedBytes, StandardCharsets.UTF_8);
                context.getLogger().log("Decoded body: " + requestBody);
            } catch (Exception e) {
                context.getLogger().log("Failed to decode base64: " + e.getMessage());
            }
        }

        JSONObject bodyJSON = new JSONObject(requestBody);

        // Safety check: ensure key exists
        String key = "index.html";
        if (bodyJSON.has("key")) {
            key = bodyJSON.getString("key");
        }

        // SECURITY: For non-index.html files, verify token using LambdaTokenChecker first
        // But allow ANY authenticated user to download (no ownership check)
        if (!key.equals("index.html")) {
            String token = bodyJSON.optString("token", null);
            String email = bodyJSON.optString("email", null);
            
            if (token == null || email == null) {
                return createErrorResponse(403, "Missing token or email");
            }

            // Invoke LambdaTokenChecker to validate token
            if (!validateTokenWithLambdaTokenChecker(email, token, context)) {
                return createErrorResponse(403, "Invalid token");
            }

            // SECURITY: Allow ANY authenticated user to download (no ownership check)
            // Only delete requires ownership verification
            context.getLogger().log("Token verified for download: " + email + ", key: " + key);
        }

        return getSpecificObject(key, context);
    }

    private APIGatewayProxyResponseEvent getSpecificObject(String key, Context context) {
        String mimeType = "application/octet-stream";
        String body = "";
        boolean isBase64 = true;
        int statusCode = 200;

        try {
            // Check metadata directly instead of listing all objects
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            HeadObjectResponse meta = s3Client.headObject(headRequest);
            long objectSize = meta.contentLength();
            int maxSize = 10 * 1024 * 1024; // 10MB

            if (objectSize < maxSize) {
                // Determine Mime Type
                String[] parts = key.split("\\.");
                if (parts.length > 1) {
                    String ext = parts[parts.length - 1].toLowerCase();
                    if (ext.equals("png"))
                        mimeType = "image/png";
                    else if (ext.equals("html"))
                        mimeType = "text/html";
                    else if (ext.equals("jpg") || ext.equals("jpeg"))
                        mimeType = "image/jpeg";
                    else if (ext.equals("txt"))
                        mimeType = "text/plain";
                }

                // Get Object
                GetObjectRequest s3Request = GetObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(key)
                        .build();

                try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(s3Request)) {
                    byte[] buffer = s3Response.readAllBytes();

                    // For HTML/text files return plain body (no base64) so browsers render
                    // correctly
                    if (mimeType.startsWith("text/html") || mimeType.startsWith("text/plain")) {
                        body = new String(buffer, StandardCharsets.UTF_8);
                        isBase64 = false;
                    } else {
                        body = Base64.getEncoder().encodeToString(buffer);
                        isBase64 = true;
                    }
                }
            } else {
                context.getLogger().log("File too large: " + objectSize);
                statusCode = 413; // Payload Too Large
            }

        } catch (S3Exception e) {
            context.getLogger().log("S3 Error: " + e.getMessage());
            statusCode = 404; // Not Found
        } catch (IOException e) {
            context.getLogger().log("IO Error: " + e.getMessage());
            statusCode = 500;
        }

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);
        response.withIsBase64Encoded(isBase64);

        // Note: CORS headers are handled by Function URL configuration
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", mimeType);
        response.setHeaders(headers);

        return response;
    }

    private APIGatewayProxyResponseEvent listObjects(Context context) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();

            // Create JSON array for frontend
            List<JSONObject> objectList = new ArrayList<>();
            for (S3Object obj : objects) {
                String key = obj.key();
                
                // Filter out index.html (frontend file) and test files
                if (key.equals("index.html") || 
                    key.startsWith("test") || 
                    key.startsWith("warmup") ||
                    key.equals("mqtt3.png")) {
                    continue; // Skip these files
                }
                
                JSONObject objJson = new JSONObject();
                objJson.put("key", key);
                objJson.put("size", obj.size());
                objJson.put("lastModified", obj.lastModified().toString());
                objectList.add(objJson);
            }

            String jsonResponse = new org.json.JSONArray(objectList).toString();

            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setBody(jsonResponse);
            response.withIsBase64Encoded(false);

            // Set headers with CORS support
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.setHeaders(headers);

            return response;

        } catch (S3Exception e) {
            context.getLogger().log("S3 Error listing objects: " + e.getMessage());
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(500);
            response.setBody("[]");
            response.withIsBase64Encoded(false);

            // Note: CORS headers are handled by Function URL configuration
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "application/json");
            response.setHeaders(headers);

            return response;
        }
    }

    // SECURITY: Validate token by getting SECRET_KEY from Parameter Store and comparing
    private boolean validateTokenWithLambdaTokenChecker(String email, String token, Context context) {
        LambdaLogger logger = context.getLogger();
        
        try {
            // 1. Get SECRET_KEY from Parameter Store
            String secretKey = getSecretKeyFromParameterStore(logger);
            if (secretKey == null || secretKey.isEmpty()) {
                logger.log("Failed to get SECRET_KEY from Parameter Store");
                return false;
            }

            // 2. Generate token from email + SECRET_KEY
            String generatedToken = generateSecureToken(email, secretKey, logger);
            if (generatedToken == null) {
                logger.log("Failed to generate token");
                return false;
            }

            // 3. Compare generated token with provided token
            boolean isValid = generatedToken.equals(token);
            logger.log("Token validation result for email " + email + ": " + isValid);

            return isValid;

        } catch (Exception e) {
            logger.log("Error validating token: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Get SECRET_KEY from SSM Parameter Store using Lambda Extension
    private String getSecretKeyFromParameterStore(LambdaLogger logger) {
        try {
            // Use AWS SDK to get parameter from Parameter Store
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name("keytokenhash") // Fixed: use correct parameter name
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

    // Generate secure token from email using HMAC-SHA256
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
    private boolean verifyPhotoOwnership(String key, String email, Context context) {
        try {
            String rdsHostname = System.getenv("RDS_HOSTNAME");
            String rdsPort = System.getenv("RDS_PORT");
            String dbUser = System.getenv("DB_USER");
            String dbPassword = System.getenv("DB_PASSWORD");
            String dbName = System.getenv("DB_NAME");

            if (rdsHostname == null || rdsPort == null || dbUser == null || dbPassword == null || dbName == null) {
                context.getLogger().log("Missing RDS environment variables");
                return false;
            }

            String jdbcUrl = "jdbc:mysql://" + rdsHostname + ":" + rdsPort + "/" + dbName;
            java.util.Properties props = new java.util.Properties();
            props.setProperty("useSSL", "true");
            props.setProperty("user", dbUser);
            props.setProperty("password", dbPassword);

            Class.forName("com.mysql.cj.jdbc.Driver");
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, props)) {
                String sql = "SELECT COUNT(*) as count FROM Photos WHERE S3Key = ? AND Email = ?";
                try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                    st.setString(1, key);
                    st.setString(2, email);
                    try (java.sql.ResultSet rs = st.executeQuery()) {
                        if (rs.next() && rs.getInt("count") > 0) {
                            return true;
                        } else {
                            context.getLogger().log("Photo " + key + " does not belong to " + email);
                            return false;
                        }
                    }
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error verifying ownership: " + e.getMessage());
            return false;
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        JSONObject error = new JSONObject();
        error.put("error", message);
        
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(error.toString());
        
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeaders(headers);
        response.setIsBase64Encoded(false);
        
        return response;
    }
}