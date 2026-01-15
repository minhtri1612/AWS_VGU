package vgu.cloud26;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaResize implements RequestHandler<S3Event, String> {

    // 1. Static Client
    private static final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    // 2. CONFIGURATION: Target Bucket for Resized Images
    private static final String DEST_BUCKET_NAME = System.getenv("DEST_BUCKET_NAME");

    static {
        if (DEST_BUCKET_NAME == null) {
            throw new RuntimeException("Missing required environment variable: DEST_BUCKET_NAME");
        }
    }

    private static final float MAX_DIMENSION = 100;
    private final String REGEX = ".*\\.([^\\.]*)";
    private final String JPG_TYPE = "jpg";
    private final String JPG_MIME = "image/jpeg";
    private final String PNG_TYPE = "png";
    private final String PNG_MIME = "image/png";

    @Override
    public String handleRequest(S3Event s3event, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            S3EventNotificationRecord record = s3event.getRecords().get(0);

            String srcBucket = record.getS3().getBucket().getName();
            String srcKey = record.getS3().getObject().getUrlDecodedKey();

            // Destination is now the DIFFERENT bucket
            String dstBucket = DEST_BUCKET_NAME;
            String dstKey = "resized-" + srcKey;

            // Infer the image type.
            Matcher matcher = Pattern.compile(REGEX).matcher(srcKey);
            if (!matcher.matches()) {
                logger.log("Unable to infer image type for key " + srcKey);
                return "";
            }
            String imageType = matcher.group(1).toLowerCase();
            // Support both .jpg and .jpeg extensions
            if (!(JPG_TYPE.equals(imageType)) && !("jpeg".equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
                logger.log("Skipping non-image " + srcKey);
                return "";
            }

            // Download from Source Bucket
            InputStream s3Object = getObject(srcBucket, srcKey);

            // Resize
            BufferedImage srcImage = ImageIO.read(s3Object);
            if (srcImage == null) {
                String errorMsg = "Could not read image: " + srcKey
                        + " - File may be corrupted or invalid image format";
                logger.log(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            BufferedImage newImage = resizeImage(srcImage);

            // Re-encode
            // Normalize "jpeg" to "jpg" for ImageIO.write() compatibility
            String outputFormat = imageType.equals("jpeg") ? "jpg" : imageType;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(newImage, outputFormat, outputStream);

            // Upload to DESTINATION Bucket
            try {
                putObject(outputStream, dstBucket, dstKey, imageType, logger);
                logger.log("Successfully resized and moved to " + dstBucket + "/" + dstKey);
                return "Object successfully resized";
            } catch (AwsServiceException e) {
                logger.log("Error writing to destination: " + e.awsErrorDetails().errorMessage());
                return e.awsErrorDetails().errorMessage();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private InputStream getObject(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return s3Client.getObject(getObjectRequest);
    }

    private void putObject(ByteArrayOutputStream outputStream,
            String bucket, String key, String imageType, LambdaLogger logger) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("Content-Length", Integer.toString(outputStream.size()));
        if (JPG_TYPE.equals(imageType)) {
            metadata.put("Content-Type", JPG_MIME);
        } else if (PNG_TYPE.equals(imageType)) {
            metadata.put("Content-Type", PNG_MIME);
        }

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .metadata(metadata)
                .build();

        s3Client.putObject(putObjectRequest,
                RequestBody.fromBytes(outputStream.toByteArray()));
    }

    private BufferedImage resizeImage(BufferedImage srcImage) {
        int srcHeight = srcImage.getHeight();
        int srcWidth = srcImage.getWidth();
        float scalingFactor = Math.min(
                MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
        int width = (int) (scalingFactor * srcWidth);
        int height = (int) (scalingFactor * srcHeight);

        BufferedImage resizedImage = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resizedImage.createGraphics();
        graphics.setPaint(Color.white);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(srcImage, 0, 0, width, height, null);
        graphics.dispose();
        return resizedImage;
    }
}