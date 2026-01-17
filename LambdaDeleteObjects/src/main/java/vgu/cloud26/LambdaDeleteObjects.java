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

    private static final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    private static final String SOURCE_BUCKET_NAME = System.getenv("BUCKET_NAME");

    static {
        if (SOURCE_BUCKET_NAME == null) {
            throw new RuntimeException("Missing required environment variable: BUCKET_NAME");
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String requestBody = request.getBody();

        context.getLogger().log("Raw request body: " + requestBody);

        // Decode base64 if needed
        if (requestBody != null && !requestBody.startsWith("{")) {
            try {
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(requestBody);
                String decodedBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                if (decodedBody.trim().startsWith("{")) {
                    requestBody = decodedBody;
                    context.getLogger().log("Successfully decoded base64 request body");
                }
            } catch (Exception e) {
                context.getLogger().log("Body is not base64 encoded, using as-is: " + e.getMessage());
            }
        }

        // Handle wrapped request from entry point
        if (requestBody != null && requestBody.startsWith("{")) {
            try {
                JSONObject testJSON = new JSONObject(requestBody);
                if (testJSON.has("body") && testJSON.has("httpMethod")) {
                    String actualBody = testJSON.getString("body");
                    context.getLogger().log("Extracted body from entry point wrapper: " + actualBody);
                    requestBody = actualBody;
                }
            } catch (Exception e) {
                context.getLogger().log("Failed to parse wrapped request: " + e.getMessage());
            }
        }

        // Validate request body
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

        // Extract keys
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

        // SECURITY: Verify token and get email
        String token = bodyJSON.optString("token", null);
        String email = verifyTokenAndGetEmail(token, context);
        if (email == null) {
            return createResponse(403, new JSONObject().put("error", "Invalid or expired token").toString());
        }

        // SECURITY: Verify ownership
        if (!verifyPhotoOwnership(keys, email, context)) {
            return createResponse(403,
                    new JSONObject().put("error", "You don't have permission to delete these photos").toString());
        }

        JSONObject result = new JSONObject();

        try {
            if (keys.size() == 1) {
                // Delete Single
                String key = keys.get(0);

                // 1. Delete from source bucket
                DeleteObjectRequest deleteSourceRequest = DeleteObjectRequest.builder()
                        .bucket(SOURCE_BUCKET_NAME)
                        .key(key)
                        .build();
                s3Client.deleteObject(deleteSourceRequest);
                context.getLogger().log("Deleted from source S3: " + key);

                // 2. Delete from database
                try {
                    deleteFromDatabase(key, context);
                    context.getLogger().log("Deleted from database: " + key);
                } catch (Exception e) {
                    context.getLogger().log("Could not delete from database: " + e.getMessage());
                }

                result.put("deleted", Collections.singletonList(key));
                result.put("message", "File deleted successfully from S3 and database");

            } else {
                // Delete Multiple
                List<ObjectIdentifier> toDeleteSource = new ArrayList<>();

                for (String k : keys) {
                    toDeleteSource.add(ObjectIdentifier.builder().key(k).build());
                }

                // 1. Delete from source bucket
                DeleteObjectsRequest deleteSourceRequest = DeleteObjectsRequest.builder()
                        .bucket(SOURCE_BUCKET_NAME)
                        .delete(Delete.builder().objects(toDeleteSource).build())
                        .build();
                DeleteObjectsResponse deleteSourceResponse = s3Client.deleteObjects(deleteSourceRequest);

                List<String> deleted = new ArrayList<>();
                deleteSourceResponse.deleted().forEach(d -> deleted.add(d.key()));
                context.getLogger().log("Deleted from source S3: " + deleted.size() + " files");

                // 2. Delete from database (batch)
                try {
                    deleteFromDatabaseBatch(keys, context);
                    context.getLogger().log("Deleted from database: " + keys.size() + " records");
                } catch (Exception e) {
                    context.getLogger().log("Could not delete from database: " + e.getMessage());
                }

                result.put("deleted", deleted);
                result.put("message", "Files deleted successfully from S3 and database");
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

    // ✅ BỔ SUNG: Delete single record from database
    private void deleteFromDatabase(String key, Context context) throws Exception {
        String rdsHostname = System.getenv("RDS_HOSTNAME");
        String rdsPort = System.getenv("RDS_PORT");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");
        String dbName = System.getenv("DB_NAME");

        if (rdsHostname == null || rdsPort == null || dbUser == null || dbPassword == null || dbName == null) {
            throw new Exception("Missing RDS environment variables");
        }

        String jdbcUrl = "jdbc:mysql://" + rdsHostname + ":" + rdsPort + "/" + dbName;
        java.util.Properties props = new java.util.Properties();
        props.setProperty("useSSL", "true");
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPassword);

        Class.forName("com.mysql.cj.jdbc.Driver");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, props)) {
            String sql = "DELETE FROM Photos WHERE S3Key = ?";
            try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                st.setString(1, key);
                int rowsDeleted = st.executeUpdate();
                context.getLogger().log("Database delete result: " + rowsDeleted + " row(s) deleted");
            }
        }
    }

    // ✅ BỔ SUNG: Delete multiple records from database (batch)
    private void deleteFromDatabaseBatch(List<String> keys, Context context) throws Exception {
        String rdsHostname = System.getenv("RDS_HOSTNAME");
        String rdsPort = System.getenv("RDS_PORT");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");
        String dbName = System.getenv("DB_NAME");

        if (rdsHostname == null || rdsPort == null || dbUser == null || dbPassword == null || dbName == null) {
            throw new Exception("Missing RDS environment variables");
        }

        String jdbcUrl = "jdbc:mysql://" + rdsHostname + ":" + rdsPort + "/" + dbName;
        java.util.Properties props = new java.util.Properties();
        props.setProperty("useSSL", "true");
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPassword);

        Class.forName("com.mysql.cj.jdbc.Driver");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, props)) {
            // Build IN clause for batch delete
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < keys.size(); i++) {
                placeholders.append("?");
                if (i < keys.size() - 1)
                    placeholders.append(",");
            }

            String sql = "DELETE FROM Photos WHERE S3Key IN (" + placeholders.toString() + ")";
            try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                for (int i = 0; i < keys.size(); i++) {
                    st.setString(i + 1, keys.get(i));
                }
                int rowsDeleted = st.executeUpdate();
                context.getLogger().log("Database batch delete result: " + rowsDeleted + " row(s) deleted");
            }
        }
    }

    // SECURITY: Verify token and get email from database
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
                            context.getLogger().log("Token verified, email: " + email);
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

    // SECURITY: Verify photo ownership
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

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);

        return response;
    }
}