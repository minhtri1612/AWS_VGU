package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaResizeWrapper implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    
    private static final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();
            
    private static final String DEST_BUCKET_NAME = System.getenv().getOrDefault("DEST_BUCKET_NAME", "minhtri-devops-cloud-resized");
    private static final String SOURCE_BUCKET_NAME = System.getenv().getOrDefault("BUCKET_NAME", "minhtri-devops-cloud-getobjects");
    private static final float MAX_DIMENSION = 100;
    private final String REGEX = ".*\\.([^\\.]*)";
    private final String JPG_TYPE = "jpg";
    private final String JPG_MIME = "image/jpeg";
    private final String PNG_TYPE = "png";
    private final String PNG_MIME = "image/png";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        
        try {
            // Parse request body
            // When invoked directly by orchestrator, event.getBody() contains a wrapped JSON with "body", "httpMethod", "headers"
            // When invoked via Function URL, event.getBody() contains the raw JSON
            String requestBody = event.getBody();
            if (requestBody == null || requestBody.isEmpty()) {
                return createErrorResponse(400, "Error: Request body is empty");
            }

            // Decode base64 if flag is set
            if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
                try {
                    byte[] decodedBytes = java.util.Base64.getDecoder().decode(requestBody);
                    requestBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                    logger.log("Decoded base64 request body (flag was set)");
                } catch (Exception e) {
                    logger.log("Failed to decode base64 body: " + e.getMessage());
                }
            }

            JSONObject bodyJSON;
            try {
                bodyJSON = new JSONObject(requestBody);
                // Check if wrapped by orchestrator (has "body" and "httpMethod" keys)
                if (bodyJSON.has("body") && bodyJSON.has("httpMethod")) {
                    // Extract the actual body from the wrapper
                    String actualBody = bodyJSON.getString("body");
                    logger.log("Extracted actual body from wrapper");
                    if (actualBody != null && !actualBody.isEmpty() && !actualBody.equals("{}")) {
                        bodyJSON = new JSONObject(actualBody);
                    } else {
                        return createErrorResponse(400, "Error: Empty body in wrapper");
                    }
                }
                logger.log("Parsed body JSON, has content: " + bodyJSON.has("content") + ", has key: " + bodyJSON.has("key"));
            } catch (Exception e) {
                // If JSON parsing failed, try base64 decoding as fallback (even if flag wasn't set)
                logger.log("Failed to parse as JSON, trying base64 decode: " + e.getMessage());
                try {
                    byte[] decodedBytes = java.util.Base64.getDecoder().decode(requestBody);
                    String decodedBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
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
                    logger.log("Parsed decoded body JSON, has content: " + bodyJSON.has("content") + ", has key: " + bodyJSON.has("key"));
                } catch (Exception decodeException) {
                    logger.log("Failed to decode base64 or parse JSON: " + decodeException.getMessage() + ", original body start: " + requestBody.substring(0, Math.min(200, requestBody.length())));
                    return createErrorResponse(400, "Error: Invalid JSON in request body: " + e.getMessage());
                }
            }

            if (!bodyJSON.has("content") || !bodyJSON.has("key")) {
                return createErrorResponse(400, "Error: Missing 'content' or 'key' field");
            }

            String content = bodyJSON.getString("content");
            String srcKey = bodyJSON.getString("key");
            String dstKey = "resized-" + srcKey;

            // Decode base64 content
            logger.log("Decoding base64 content, length: " + content.length());
            byte[] imageBytes;
            try {
                imageBytes = Base64.getDecoder().decode(content);
                logger.log("Decoded image bytes, size: " + imageBytes.length);
            } catch (IllegalArgumentException e) {
                logger.log("Invalid base64 content: " + e.getMessage());
                return createErrorResponse(400, "Error: Invalid base64 content: " + e.getMessage());
            }
            
            // Infer image type
            Matcher matcher = Pattern.compile(REGEX).matcher(srcKey);
            if (!matcher.matches()) {
                return createErrorResponse(400, "Unable to infer image type for key " + srcKey);
            }
            String imageType = matcher.group(1).toLowerCase();
            logger.log("Detected image type: " + imageType);
            if (!(JPG_TYPE.equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
                return createErrorResponse(400, "Skipping non-image " + srcKey);
            }

            // Read and resize image
            ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);
            BufferedImage srcImage = ImageIO.read(inputStream);
            if (srcImage == null) {
                logger.log("ImageIO.read returned null for key: " + srcKey + ", image bytes length: " + imageBytes.length);
                return createErrorResponse(400, "Could not read image: " + srcKey + " (possibly unsupported format or corrupted data)");
            }
            logger.log("Successfully read image, dimensions: " + srcImage.getWidth() + "x" + srcImage.getHeight());
            
            BufferedImage newImage = resizeImage(srcImage);
            
            // Re-encode
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(newImage, imageType, outputStream);

            // Upload to resized bucket
            Map<String, String> metadata = new HashMap<>();
            metadata.put("Content-Length", Integer.toString(outputStream.size()));
            if (JPG_TYPE.equals(imageType)) {
                metadata.put("Content-Type", JPG_MIME);
            } else if (PNG_TYPE.equals(imageType)) {
                metadata.put("Content-Type", PNG_MIME);
            }

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(DEST_BUCKET_NAME)
                    .key(dstKey)
                    .metadata(metadata)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(outputStream.toByteArray()));
            
            logger.log("Successfully resized and uploaded to " + DEST_BUCKET_NAME + "/" + dstKey);
            
            response.setStatusCode(200);
            response.setBody("Object successfully resized and uploaded");
            response.withIsBase64Encoded(false);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/plain");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type");
            response.setHeaders(headers);
            
            return response;
            
        } catch (Exception e) {
            logger.log("Error in resize: " + e.getMessage());
            return createErrorResponse(500, "Error resizing image: " + e.getMessage());
        }
    }

    private BufferedImage resizeImage(BufferedImage srcImage) {
        int srcHeight = srcImage.getHeight();
        int srcWidth = srcImage.getWidth();
        float scalingFactor = Math.min(MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
        int width = (int) (scalingFactor * srcWidth);
        int height = (int) (scalingFactor * srcHeight);

        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resizedImage.createGraphics();
        graphics.setPaint(Color.white);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(srcImage, 0, 0, width, height, null);
        graphics.dispose();
        return resizedImage;
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(message);
        response.withIsBase64Encoded(false);
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        response.setHeaders(headers);
        
        return response;
    }
}

