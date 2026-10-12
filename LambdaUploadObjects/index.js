const { S3Client, PutObjectCommand } = require("@aws-sdk/client-s3");

const BUCKET_NAME = process.env.BUCKET_NAME;
const REGION = process.env.AWS_REGION || "ap-southeast-2";
const s3Client = new S3Client({ region: REGION });

const HEADERS = {
  "Content-Type": "text/plain",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function textResponse(statusCode, body) {
  return { statusCode, isBase64Encoded: false, headers: HEADERS, body };
}

exports.handler = async (event) => {
  if (!BUCKET_NAME) {
    throw new Error("Missing required environment variable: BUCKET_NAME");
  }

  try {
    let requestBody = event.body;
    console.log("Raw request body: " + requestBody);
    console.log("Is base64 encoded: " + event.isBase64Encoded);

    if (requestBody && !requestBody.startsWith("{") && /^[A-Za-z0-9+/]*={0,2}$/.test(requestBody)) {
      try {
        requestBody = Buffer.from(requestBody, "base64").toString("utf8");
        console.log("Decoded base64 request body: " + requestBody);
      } catch (err) {
        console.log("Failed to decode base64: " + err.message);
      }
    }

    let bodyJSON = {};
    if (requestBody && requestBody.startsWith("{")) {
      try {
        bodyJSON = JSON.parse(requestBody);
        if (Object.prototype.hasOwnProperty.call(bodyJSON, "body") && Object.prototype.hasOwnProperty.call(bodyJSON, "httpMethod")) {
          const actualBody = bodyJSON.body;
          console.log("Extracted body from entry point wrapper: " + actualBody);
          bodyJSON = JSON.parse(actualBody);
        }
      } catch (err) {
        console.log("Failed to parse request body: " + err.message);
        bodyJSON = {};
      }
    }
    console.log("Parsed JSON keys: " + Object.keys(bodyJSON).join(","));

    if (!Object.prototype.hasOwnProperty.call(bodyJSON, "content")) {
      throw new Error("Missing 'content' field in request body");
    }
    if (!Object.prototype.hasOwnProperty.call(bodyJSON, "key")) {
      throw new Error("Missing 'key' field in request body");
    }

    const content = bodyJSON.content;
    const objName = bodyJSON.key;
    console.log("Content length: " + content.length + ", Object name: " + objName);
    const objBytes = Buffer.from(content, "base64");

    console.log("Uploading to S3 bucket: " + BUCKET_NAME + ", key: " + objName + ", size: " + objBytes.length + " bytes");
    await s3Client.send(new PutObjectCommand({ Bucket: BUCKET_NAME, Key: objName, Body: objBytes }));
    console.log("Upload to S3 completed successfully");
    return textResponse(200, "Object uploaded successfully");
  } catch (err) {
    console.log("Upload failed: " + err.message);
    return textResponse(500, "Upload failed: " + err.message);
  }
};
