const { S3Client, DeleteObjectCommand } = require("@aws-sdk/client-s3");

const s3Client = new S3Client({ region: "ap-southeast-2" });
const RESIZED_BUCKET_NAME = process.env.RESIZED_BUCKET_NAME;

function createResponse(statusCode, body) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: { "Content-Type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  };
}

exports.handler = async (event) => {
  if (!RESIZED_BUCKET_NAME) {
    throw new Error("Missing required environment variable: RESIZED_BUCKET_NAME");
  }

  try {
    let requestBody = event.body;
    if (requestBody && !requestBody.startsWith("{")) {
      try {
        requestBody = Buffer.from(requestBody, "base64").toString("utf8");
        console.log("Decoded base64 request body");
      } catch (err) {
        console.log("Body is not base64 encoded: " + err.message);
      }
    }

    let bodyJSON;
    try {
      bodyJSON = JSON.parse(requestBody || "{}");
      if (bodyJSON && typeof bodyJSON.body === "string" && Object.prototype.hasOwnProperty.call(bodyJSON, "httpMethod")) {
        if (bodyJSON.body) {
          bodyJSON = JSON.parse(bodyJSON.body);
        }
      }
    } catch (err) {
      console.log("Error parsing JSON: " + err.message);
      return createResponse(400, { error: "Invalid JSON: " + err.message });
    }

    const key = bodyJSON.key || "";
    if (!key) {
      return createResponse(400, { error: "Missing 'key' field" });
    }

    const resizedKey = "resized-" + key;
    console.log("Deleting from resized S3 bucket: " + resizedKey);
    await s3Client.send(new DeleteObjectCommand({ Bucket: RESIZED_BUCKET_NAME, Key: resizedKey }));
    console.log("Successfully deleted from resized bucket: " + resizedKey);
    return createResponse(200, {
      message: "Success: Deleted from resized S3 bucket",
      key: resizedKey,
      bucket: RESIZED_BUCKET_NAME,
    });
  } catch (err) {
    console.log("ERROR deleting from resized bucket (may not exist): " + err.message);
    return createResponse(200, {
      error: "Resized bucket delete failed (may not exist): " + err.message,
    });
  }
};
