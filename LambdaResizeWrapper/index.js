const { S3Client, PutObjectCommand } = require("@aws-sdk/client-s3");
const sharp = require("sharp");

const s3Client = new S3Client({ region: "ap-southeast-2" });
const DEST_BUCKET_NAME = process.env.DEST_BUCKET_NAME;
const SOURCE_BUCKET_NAME = process.env.BUCKET_NAME;
const MAX_DIMENSION = 100;

const HEADERS = {
  "Content-Type": "text/plain",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function textResponse(statusCode, body) {
  return { statusCode, isBase64Encoded: false, headers: HEADERS, body };
}

function parseBody(event) {
  let requestBody = event.body;
  if (requestBody == null || requestBody === "") {
    return { error: textResponse(400, "Error: Request body is empty") };
  }

  if (event.isBase64Encoded) {
    try {
      requestBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Decoded base64 request body (flag was set)");
    } catch (err) {
      console.log("Failed to decode base64 body: " + err.message);
    }
  }

  let bodyJSON;
  try {
    bodyJSON = JSON.parse(requestBody);
  } catch (err) {
    console.log("Failed to parse as JSON, trying base64 decode: " + err.message);
    try {
      const decodedBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Successfully decoded base64, now parsing JSON");
      bodyJSON = JSON.parse(decodedBody);
    } catch (decodeErr) {
      console.log(
        "Failed to decode base64 or parse JSON: " +
          decodeErr.message +
          ", original body start: " +
          requestBody.slice(0, 200)
      );
      return { error: textResponse(400, "Error: Invalid JSON in request body: " + err.message) };
    }
  }

  if (bodyJSON && Object.prototype.hasOwnProperty.call(bodyJSON, "body") && Object.prototype.hasOwnProperty.call(bodyJSON, "httpMethod")) {
    const actualBody = bodyJSON.body;
    console.log("Extracted actual body from wrapper");
    if (actualBody && actualBody !== "{}") {
      bodyJSON = typeof actualBody === "string" ? JSON.parse(actualBody) : actualBody;
    } else {
      return { error: textResponse(400, "Error: Empty body in wrapper") };
    }
  }

  console.log("Parsed body JSON, has content: " + (bodyJSON.content != null) + ", has key: " + (bodyJSON.key != null));
  return { bodyJSON };
}

async function resizeImage(input) {
  const meta = await sharp(input).metadata();
  const scalingFactor = Math.min(MAX_DIMENSION / meta.width, MAX_DIMENSION / meta.height);
  const width = Math.floor(scalingFactor * meta.width);
  const height = Math.floor(scalingFactor * meta.height);
  return sharp(input)
    .resize(Math.max(1, width), Math.max(1, height), { fit: "fill", kernel: "linear" })
    .flatten({ background: "#ffffff" })
    .toBuffer();
}

exports.handler = async (event) => {
  if (!DEST_BUCKET_NAME || !SOURCE_BUCKET_NAME) {
    throw new Error("Missing required environment variables: DEST_BUCKET_NAME, BUCKET_NAME");
  }

  try {
    const parsed = parseBody(event);
    if (parsed.error) return parsed.error;
    const bodyJSON = parsed.bodyJSON;

    if (bodyJSON.content == null || bodyJSON.key == null) {
      return textResponse(400, "Error: Missing 'content' or 'key' field");
    }

    const content = bodyJSON.content;
    const srcKey = bodyJSON.key;
    const dstKey = "resized-" + srcKey;
    console.log("Decoding base64 content, length: " + content.length);

    let imageBytes;
    try {
      imageBytes = Buffer.from(content, "base64");
      if (!imageBytes.length && content) {
        throw new Error("Invalid base64 content");
      }
      console.log("Decoded image bytes, size: " + imageBytes.length);
    } catch (err) {
      console.log("Invalid base64 content: " + err.message);
      return textResponse(400, "Error: Invalid base64 content: " + err.message);
    }

    const match = /.*\.([^.]*)$/.exec(srcKey);
    if (!match) {
      return textResponse(400, "Unable to infer image type for key " + srcKey);
    }
    const imageType = match[1].toLowerCase();
    console.log("Detected image type: " + imageType);
    if (imageType !== "jpg" && imageType !== "png") {
      return textResponse(400, "Skipping non-image " + srcKey);
    }

    let resized;
    try {
      resized = await resizeImage(imageBytes);
    } catch (err) {
      console.log("Image read failed for key: " + srcKey + ", image bytes length: " + imageBytes.length);
      return textResponse(400, "Could not read image: " + srcKey + " (possibly unsupported format or corrupted data)");
    }

    const contentType = imageType === "jpg" ? "image/jpeg" : "image/png";
    const output = imageType === "png" ? await sharp(resized).png().toBuffer() : await sharp(resized).jpeg().toBuffer();

    await s3Client.send(
      new PutObjectCommand({
        Bucket: DEST_BUCKET_NAME,
        Key: dstKey,
        Body: output,
        Metadata: {
          "Content-Length": String(output.length),
          "Content-Type": contentType,
        },
      })
    );
    console.log("Successfully resized and uploaded to " + DEST_BUCKET_NAME + "/" + dstKey);
    return textResponse(200, "Object successfully resized and uploaded");
  } catch (err) {
    console.log("Error in resize: " + err.message);
    return textResponse(500, "Error resizing image: " + err.message);
  }
};

exports.parseBody = parseBody;
exports.resizeImage = resizeImage;
