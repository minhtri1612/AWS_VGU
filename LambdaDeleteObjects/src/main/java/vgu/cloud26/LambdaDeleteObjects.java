package vgu.cloud26;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class LambdaDeleteObjects implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // 1. OPTIMIZATION: Static Client
    private static final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    // 2. CONFIGURATION: Dynamic Bucket Names
    private static final String SOURCE_BUCKET_NAME = System.getenv("BUCKET_NAME");
    private static final String RESIZED_BUCKET_NAME = System.getenv("RESIZED_BUCKET_NAME");

    static {
        if (SOURCE_BUCKET_NAME == null || RESIZED_BUCKET_NAME == null) {
            throw new RuntimeException("Missing required environment variables: BUCKET_NAME, RESIZED_BUCKET_NAME");
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        // Note: OPTIONS preflight is handled by Function URL CORS configuration
        String requestBody = request.getBody();

        context.getLogger().log("Raw request body: " + requestBody);

        // Check if the body is base64 encoded
        if (requestBody != null && !requestBody.startsWith("{")) {
            try {
                // Try to decode as base64
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(requestBody);
                String decodedBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);

                // If decoded successfully and looks like JSON, use it
                if (decodedBody.trim().startsWith("{")) {
                    requestBody = decodedBody;
                    context.getLogger().log("Successfully decoded base64 request body: " + requestBody);
                }
            } catch (Exception e) {
                context.getLogger().log("Body is not base64 encoded, using as-is: " + e.getMessage());
                // If decoding fails, continue with original body
            }
        }

        // Handle both direct calls and calls through entry point
        if (requestBody != null && requestBody.startsWith("{")) {
            try {
                JSONObject testJSON = new JSONObject(requestBody);
                // Check if this looks like a wrapped request from entry point
                if (testJSON.has("body") && testJSON.has("httpMethod")) {
                    // This is a wrapped request from entry point, extract the actual body
                    String actualBody = testJSON.getString("body");
                    context.getLogger().log("Extracted body from entry point wrapper: " + actualBody);
                    requestBody = actualBody;
                }
            } catch (Exception e) {
                context.getLogger().log("Failed to parse wrapped request: " + e.getMessage());
            }
        }

        // Check if requestBody is null or empty
        if (requestBody == null || requestBody.trim().isEmpty()) {
            context.getLogger().log("Request body is null or empty");
            return createResponse(400, new JSONObject().put("error", "Request body is required").toString());
        }

        context.getLogger().log("Final request body to parse: " + requestBody);

        JSONObject bodyJSON;
        try {
            bodyJSON = new JSONObject(requestBody);
        } catch (Exception e) {
            context.getLogger().log("Failed to parse JSON: " + e.getMessage());
            return createResponse(400, new JSONObject().put("error", "Invalid JSON: " + e.getMessage()).toString());
        }

        List<String> keys = new ArrayList<>();

        // Logic to extract keys
        if (bodyJSON.has("key")) {
            keys.add(bodyJSON.getString("key"));
        } else if (bodyJSON.has("keys")) {
            JSONArray arr = bodyJSON.getJSONArray("keys");
            for (int i = 0; i < arr.length(); i++) {
                keys.add(arr.getString(i));
            }
        } else {
            return createResponse(400, new JSONObject().put("error", "Missing 'key' or 'keys' field").toString());
        }

        // SECURITY: Don't trust the client - verify token and get email from token
        String token = bodyJSON.optString("token", null);
        String email = verifyTokenAndGetEmail(token, context);
        if (email == null) {
            return createResponse(403, new JSONObject().put("error", "Invalid or expired token").toString());
        }

        // SECURITY: Verify ownership - check if all photos belong to the authenticated user
        if (!verifyPhotoOwnership(keys, email, context)) {
            return createResponse(403, new JSONObject().put("error", "You don't have permission to delete these photos").toString());
        }

        JSONObject result = new JSONObject();

        try {
            if (keys.size() == 1) {
                // Delete Single - from both buckets
                String key = keys.get(0);

                // Delete from source bucket
                DeleteObjectRequest deleteSourceRequest = DeleteObjectRequest.builder()
                        .bucket(SOURCE_BUCKET_NAME)
                        .key(key)
                        .build();
                s3Client.deleteObject(deleteSourceRequest);

                // Delete from resized bucket (with "resized-" prefix)
                try {
                    String resizedKey = "resized-" + key;
                    DeleteObjectRequest deleteResizedRequest = DeleteObjectRequest.builder()
                            .bucket(RESIZED_BUCKET_NAME)
                            .key(resizedKey)
                            .build();
                    s3Client.deleteObject(deleteResizedRequest);
                    context.getLogger()
                            .log("Deleted from both source (" + key + ") and resized (" + resizedKey + ") buckets");
                } catch (Exception e) {
                    context.getLogger().log("Could not delete from resized bucket (may not exist): " + e.getMessage());
                }

                result.put("deleted", Collections.singletonList(key));
                result.put("message", "File deleted successfully from both buckets");

            } else {
                // Delete Multiple - from both buckets
                List<ObjectIdentifier> toDeleteSource = new ArrayList<>();
                List<ObjectIdentifier> toDeleteResized = new ArrayList<>();

                for (String k : keys) {
                    toDeleteSource.add(ObjectIdentifier.builder().key(k).build());
                    toDeleteResized.add(ObjectIdentifier.builder().key("resized-" + k).build());
                }

                // Delete from source bucket
                DeleteObjectsRequest deleteSourceRequest = DeleteObjectsRequest.builder()
                        .bucket(SOURCE_BUCKET_NAME)
                        .delete(Delete.builder().objects(toDeleteSource).build())
                        .build();
                DeleteObjectsResponse deleteSourceResponse = s3Client.deleteObjects(deleteSourceRequest);

                // Delete from resized bucket
                List<String> deleted = new ArrayList<>();
                deleteSourceResponse.deleted().forEach(d -> deleted.add(d.key()));

                try {
                    DeleteObjectsRequest deleteResizedRequest = DeleteObjectsRequest.builder()
                            .bucket(RESIZED_BUCKET_NAME)
                            .delete(Delete.builder().objects(toDeleteResized).build())
                            .build();
                    s3Client.deleteObjects(deleteResizedRequest);
                    context.getLogger().log("Deleted from both source and resized buckets");
                } catch (Exception e) {
                    context.getLogger().log("Could not delete from resized bucket: " + e.getMessage());
                }

                result.put("deleted", deleted);
                result.put("message", "Files deleted successfully from both buckets");
            }

            return createResponse(200, result.toString());

        } catch (S3Exception e) {
            context.getLogger().log("S3 Error: " + e.awsErrorDetails().errorMessage());
            return createResponse(500, new JSONObject().put("error", "S3Exception: " + e.getMessage()).toString());
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return createResponse(500, new JSONObject().put("error", "Exception: " + e.getMessage()).toString());
        }
    }

    // SECURITY: Verify token and get email from database (don't trust client)
    private String verifyTokenAndGetEmail(String token, Context context) {
        if (token == null || token.isEmpty()) {
            context.getLogger().log("No token provided");
            return null;
        }

        try {
            String rdsHostname = System.getenv("RDS_HOSTNAME");
            String rdsPort = System.getenv("RDS_PORT");
            String dbUser = System.getenv("DB_USER");
            String dbPassword = System.getenv("DB_PASSWORD");
            String dbName = System.getenv("DB_NAME");

            if (rdsHostname == null || rdsPort == null || dbUser == null || dbPassword == null || dbName == null) {
                context.getLogger().log("Missing RDS environment variables");
                return null;
            }

            String jdbcUrl = "jdbc:mysql://" + rdsHostname + ":" + rdsPort + "/" + dbName;
            java.util.Properties props = new java.util.Properties();
            props.setProperty("useSSL", "true");
            props.setProperty("user", dbUser);
            props.setProperty("password", dbPassword);

            Class.forName("com.mysql.cj.jdbc.Driver");
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, props)) {
                String sql = "SELECT Email FROM Tokens WHERE Token = ? AND ExpiresAt > NOW()";
                try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                    st.setString(1, token);
                    try (java.sql.ResultSet rs = st.executeQuery()) {
                        if (rs.next()) {
                            String email = rs.getString("Email");
                            context.getLogger().log("Token verified, email from token: " + email);
                            return email;
                        } else {
                            context.getLogger().log("Invalid or expired token");
                            return null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error verifying token: " + e.getMessage());
            return null;
        }
    }

    // SECURITY: Verify that all photos belong to the authenticated user
    private boolean verifyPhotoOwnership(List<String> keys, String email, Context context) {
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
                // Check if all photos belong to this email
                for (String key : keys) {
                    String sql = "SELECT COUNT(*) as count FROM Photos WHERE S3Key = ? AND Email = ?";
                    try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                        st.setString(1, key);
                        st.setString(2, email);
                        try (java.sql.ResultSet rs = st.executeQuery()) {
                            if (rs.next() && rs.getInt("count") == 0) {
                                context.getLogger().log("Photo " + key + " does not belong to " + email);
                                return false;
                            }
                        }
                    }
                }
                return true;
            }
        } catch (Exception e) {
            context.getLogger().log("Error verifying ownership: " + e.getMessage());
            return false;
        }
    }

    // Helper method to keep code clean
    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);

        // Note: CORS headers are handled by Function URL configuration
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);

        return response;
    }
}