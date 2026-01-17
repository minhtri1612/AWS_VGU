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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaGetResizedImage
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RESIZED_BUCKET_NAME = System.getenv("RESIZED_BUCKET_NAME");
  private static final String SOURCE_BUCKET_NAME = System.getenv("SOURCE_BUCKET_NAME");
  private static final float MAX_DIMENSION = 100;
  private static final String REGEX = ".*\\.([^\\.]*)";
  private static final String JPG_TYPE = "jpg";
  private static final String JPG_MIME = "image/jpeg";
  private static final String PNG_TYPE = "png";
  private static final String PNG_MIME = "image/png";

  static {
    if (RESIZED_BUCKET_NAME == null) {
      throw new RuntimeException("Missing required environment variable: RESIZED_BUCKET_NAME");
    }
    if (SOURCE_BUCKET_NAME == null) {
      throw new RuntimeException("Missing required environment variable: SOURCE_BUCKET_NAME");
    }
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();

    try {
      // Handle OPTIONS preflight for CORS
      if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
        return createCorsResponse();
      }

      // 1. Parse the requested filename
      // Supports both POST body {"key":"..."} or Query String ?key=...
      String originalKey = "";
      
      // First check query string parameters (from API Gateway or LambdaEntryPoint)
      if (event.getQueryStringParameters() != null && event.getQueryStringParameters().containsKey("key")) {
        originalKey = event.getQueryStringParameters().get("key");
        logger.log("Found key in queryStringParameters: " + originalKey);
      }
      
      // If not in query string, check body
      if (originalKey.isEmpty() && event.getBody() != null && !event.getBody().isEmpty() && !event.getBody().equals("{}")) {
        String requestBody = event.getBody();
        logger.log("Raw body: " + requestBody);
        
        // Decode base64 if needed (check flag or auto-detect)
        if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
            requestBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
            logger.log("Decoded base64 body (from flag): " + requestBody);
          } catch (Exception e) {
            logger.log("Failed to decode base64: " + e.getMessage());
          }
        } else if (!requestBody.startsWith("{")) {
          // Auto-detect base64: if body doesn't start with '{', try to decode
          try {
            byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
            String decodedBody = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (decodedBody.trim().startsWith("{")) {
              requestBody = decodedBody;
              logger.log("Auto-decoded base64 body: " + requestBody);
            }
          } catch (Exception e) {
            logger.log("Body is not base64, using as-is");
          }
        }
        
        // Check if body is wrapped (from LambdaEntryPoint) - unwrap it
        try {
          JSONObject wrapper = new JSONObject(requestBody);
          if (wrapper.has("body")) {
            requestBody = wrapper.getString("body");
            logger.log("Unwrapped body from LambdaEntryPoint: " + requestBody);
          }
        } catch (Exception wrapperEx) {
          // Not a wrapper, continue with original body
          logger.log("Body is not a wrapper, using as-is");
        }
        
        // Try to parse as JSON
        try {
          JSONObject body = new JSONObject(requestBody);
          if (body.has("key")) {
            originalKey = body.getString("key");
            logger.log("Found key in body: " + originalKey);
          } else {
            logger.log("Body JSON does not contain 'key' field. Available fields: " + body.toString());
          }
        } catch (Exception jsonEx) {
          logger.log("Body is not valid JSON: " + jsonEx.getMessage() + ", body: " + requestBody);
        }
      }

      if (originalKey == null || originalKey.isEmpty()) {
        logger.log("Missing 'key' parameter - queryString: " + (event.getQueryStringParameters() != null ? event.getQueryStringParameters().toString() : "null") + ", body: " + (event.getBody() != null ? event.getBody() : "null"));
        return createErrorResponse(400, "Missing 'key' parameter");
      }

    // 2. Calculate Resized Key
    String resizedKey = "resized-" + originalKey;

    S3Client s3 = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();

    try {
      // 3. Try to get resized image from S3
      GetObjectRequest getRequest = GetObjectRequest.builder().bucket(RESIZED_BUCKET_NAME).key(resizedKey).build();

      ResponseBytes<GetObjectResponse> objectBytes = s3.getObjectAsBytes(getRequest);
      byte[] data = objectBytes.asByteArray();
      String contentType = objectBytes.response().contentType();

      // 4. Convert to Base64
      String base64Data = Base64.getEncoder().encodeToString(data);

      // 5. Return Image Response with CORS headers
      Map<String, String> headers = new java.util.HashMap<>();
      headers.put("Content-Type", contentType != null ? contentType : "image/jpeg");
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(headers)
          .withBody(base64Data)
          .withIsBase64Encoded(true);

    } catch (NoSuchKeyException e) {
      // Resized image doesn't exist - try to create it on-demand from original
      logger.log("Resized image not found: " + resizedKey + ", attempting on-demand resize");
      try {
        return createResizedImageOnDemand(originalKey, resizedKey, s3, logger);
      } catch (Exception resizeEx) {
        logger.log("Failed to create resized image on-demand: " + resizeEx.getMessage());
        resizeEx.printStackTrace();
        return createErrorResponse(404, "Image not found");
      }
    } catch (Exception e) {
      logger.log("Error fetching resized image: " + e.getMessage());
      e.printStackTrace();
      return createErrorResponse(404, "Image not found");
    }
    } catch (Exception e) {
      logger.log("Fatal error in handleRequest: " + e.getMessage());
      e.printStackTrace();
      return createErrorResponse(500, "Internal server error");
    }
  }

  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "text/plain");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(headers)
        .withBody(message);
  }

  private APIGatewayProxyResponseEvent createCorsResponse() {
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withHeaders(headers)
        .withBody("");
  }

  // On-demand resize: create resized image from original if it doesn't exist
  private APIGatewayProxyResponseEvent createResizedImageOnDemand(
      String originalKey, String resizedKey, S3Client s3, LambdaLogger logger) throws Exception {
    
    // Infer image type
    Matcher matcher = Pattern.compile(REGEX).matcher(originalKey);
    if (!matcher.matches()) {
      throw new Exception("Unable to infer image type for key " + originalKey);
    }
    String imageType = matcher.group(1).toLowerCase();
    if (!JPG_TYPE.equals(imageType) && !"jpeg".equals(imageType) && !PNG_TYPE.equals(imageType)) {
      throw new Exception("Skipping non-image " + originalKey);
    }

    // Get original image from source bucket
    logger.log("Downloading original image from " + SOURCE_BUCKET_NAME + "/" + originalKey);
    GetObjectRequest getOriginalRequest = GetObjectRequest.builder()
        .bucket(SOURCE_BUCKET_NAME)
        .key(originalKey)
        .build();
    
    InputStream originalStream = s3.getObject(getOriginalRequest);
    BufferedImage srcImage = ImageIO.read(originalStream);
    if (srcImage == null) {
      throw new Exception("Could not read image: " + originalKey);
    }

    // Resize image
    BufferedImage resizedImage = resizeImage(srcImage);

    // Re-encode
    String outputFormat = imageType.equals("jpeg") ? "jpg" : imageType;
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(resizedImage, outputFormat, outputStream);

    // Upload to resized bucket
    logger.log("Uploading resized image to " + RESIZED_BUCKET_NAME + "/" + resizedKey);
    Map<String, String> metadata = new java.util.HashMap<>();
    metadata.put("Content-Length", Integer.toString(outputStream.size()));
    if (JPG_TYPE.equals(imageType) || "jpeg".equals(imageType)) {
      metadata.put("Content-Type", JPG_MIME);
    } else if (PNG_TYPE.equals(imageType)) {
      metadata.put("Content-Type", PNG_MIME);
    }

    PutObjectRequest putRequest = PutObjectRequest.builder()
        .bucket(RESIZED_BUCKET_NAME)
        .key(resizedKey)
        .metadata(metadata)
        .contentType(metadata.get("Content-Type"))
        .build();

    s3.putObject(putRequest, RequestBody.fromBytes(outputStream.toByteArray()));
    logger.log("Successfully created resized image: " + resizedKey);

    // Return the resized image
    String base64Data = Base64.getEncoder().encodeToString(outputStream.toByteArray());
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", metadata.get("Content-Type"));
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withHeaders(headers)
        .withBody(base64Data)
        .withIsBase64Encoded(true);
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
}
