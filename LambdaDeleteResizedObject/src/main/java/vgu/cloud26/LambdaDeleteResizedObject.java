package vgu.cloud26; // Change to your package name

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

public class LambdaDeleteResizedObject implements RequestHandler<S3Event, String> {

  // 1. Initialize the S3 Client (outside the handler for efficiency)
  private final AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();

  // 2. Define the name of your resized bucket from environment variable
  private final String RESIZED_BUCKET_NAME;

  public LambdaDeleteResizedObject() {
    RESIZED_BUCKET_NAME = System.getenv("RESIZED_BUCKET_NAME");
    if (RESIZED_BUCKET_NAME == null) {
      throw new RuntimeException("Missing required environment variable: RESIZED_BUCKET_NAME");
    }
  }

  @Override
  public String handleRequest(S3Event event, Context context) {
    context.getLogger().log("Received S3 Event: " + event.toString());

    // 3. Loop through every record in the event (usually just one)
    for (S3EventNotificationRecord record : event.getRecords()) {

      // 4. Extract the Key (filename) of the deleted original image
      String originalKey = record.getS3().getObject().getKey();
      context.getLogger().log("Original image deleted: " + originalKey);

      // 5. Calculate the name of the resized image
      // WARNING: Change this logic to match how you named your resized files!
      // Example: If original is "dog.png", resized might be "resized-dog.png"
      String resizedKey = "resized-" + originalKey;

      try {
        // 6. Delete the corresponding file from the Resized Bucket
        s3Client.deleteObject(RESIZED_BUCKET_NAME, resizedKey);
        context.getLogger().log("Successfully deleted resized image: " + resizedKey);

      } catch (Exception e) {
        context.getLogger().log("Error deleting file: " + e.getMessage());
        return "Error";
      }
    }
    return "Success";
  }
}
