const { S3Client, GetObjectCommand, PutObjectCommand } = require("@aws-sdk/client-s3");
const sharp = require("sharp");

const REGION = "ap-southeast-2";
const s3 = new S3Client({ region: REGION });
const RESIZED_BUCKET_NAME = process.env.RESIZED_BUCKET_NAME;
const SOURCE_BUCKET_NAME = process.env.SOURCE_BUCKET_NAME;
const MAX_DIMENSION = 100;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function errorResponse(statusCode, message) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: { "Content-Type": "text/plain", ...CORS },
    body: message,
  };
}

function imageResponse(bytes, contentType) {
  return {
    statusCode: 200,
    isBase64Encoded: true,
    headers: {
      "Content-Type": contentType || "image/jpeg",
      ...CORS,
    },
    body: Buffer.from(bytes).toString("base64"),
  };
}

async function streamToBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function readKey(event) {
  const query = event.queryStringParameters;
  if (query && query.key) {
    console.log("Found key in queryStringParameters: " + query.key);
    return query.key;
  }

  let requestBody = event.body;
  if (requestBody == null || requestBody === "" || requestBody === "{}") {
    return "";
  }
  console.log("Raw body: " + requestBody);

  if (event.isBase64Encoded) {
    try {
      requestBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Decoded base64 body (from flag): " + requestBody);
    } catch (err) {
      console.log("Failed to decode base64: " + err.message);
    }
  } else if (!requestBody.startsWith("{")) {
    try {
      const decodedBody = Buffer.from(requestBody, "base64").toString("utf8");
      if (decodedBody.trim().startsWith("{")) {
        requestBody = decodedBody;
        console.log("Auto-decoded base64 body: " + requestBody);
      }
    } catch (err) {
      console.log("Body is not base64, using as-is");
    }
  }

  try {
    const wrapper = JSON.parse(requestBody);
    if (wrapper && typeof wrapper.body === "string") {
      requestBody = wrapper.body;
      console.log("Unwrapped body from LambdaEntryPoint: " + requestBody);
    }
  } catch (err) {
    console.log("Body is not a wrapper, using as-is");
  }

  try {
    const body = JSON.parse(requestBody);
    if (body && Object.prototype.hasOwnProperty.call(body, "key")) {
      console.log("Found key in body: " + body.key);
      return body.key;
    }
    console.log("Body JSON does not contain 'key' field. Available fields: " + JSON.stringify(body));
  } catch (err) {
    console.log("Body is not valid JSON: " + err.message + ", body: " + requestBody);
  }
  return "";
}

function imageTypeOf(key) {
  const match = /.*\.([^.]*)$/.exec(key);
  if (!match) {
    throw new Error("Unable to infer image type for key " + key);
  }
  const imageType = match[1].toLowerCase();
  if (imageType !== "jpg" && imageType !== "jpeg" && imageType !== "png") {
    throw new Error("Skipping non-image " + key);
  }
  return imageType;
}

async function resizeImage(input) {
  const meta = await sharp(input).metadata();
  const srcWidth = meta.width;
  const srcHeight = meta.height;
  const scalingFactor = Math.min(MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
  const width = Math.max(1, Math.floor(scalingFactor * srcWidth));
  const height = Math.max(1, Math.floor(scalingFactor * srcHeight));
  return sharp(input)
    .resize(width, height, { fit: "fill", kernel: "linear" })
    .flatten({ background: "#ffffff" })
    .toBuffer();
}

async function createResizedImageOnDemand(originalKey, resizedKey) {
  const imageType = imageTypeOf(originalKey);
  console.log("Downloading original image from " + SOURCE_BUCKET_NAME + "/" + originalKey);
  const original = await s3.send(new GetObjectCommand({ Bucket: SOURCE_BUCKET_NAME, Key: originalKey }));
  const src = await streamToBuffer(original.Body);
  const outputFormat = imageType === "jpeg" ? "jpg" : imageType;
  let pipeline = sharp(await resizeImage(src));
  const contentType = outputFormat === "png" ? "image/png" : "image/jpeg";
  const bytes = outputFormat === "png" ? await pipeline.png().toBuffer() : await pipeline.jpeg().toBuffer();

  console.log("Uploading resized image to " + RESIZED_BUCKET_NAME + "/" + resizedKey);
  await s3.send(
    new PutObjectCommand({
      Bucket: RESIZED_BUCKET_NAME,
      Key: resizedKey,
      Body: bytes,
      ContentType: contentType,
      Metadata: {
        "Content-Length": String(bytes.length),
        "Content-Type": contentType,
      },
    })
  );
  console.log("Successfully created resized image: " + resizedKey);
  return imageResponse(bytes, contentType);
}

exports.handler = async (event) => {
  if (!RESIZED_BUCKET_NAME) {
    throw new Error("Missing required environment variable: RESIZED_BUCKET_NAME");
  }
  if (!SOURCE_BUCKET_NAME) {
    throw new Error("Missing required environment variable: SOURCE_BUCKET_NAME");
  }

  try {
    if (event.httpMethod && event.httpMethod.toUpperCase() === "OPTIONS") {
      return { statusCode: 200, isBase64Encoded: false, headers: CORS, body: "" };
    }

    const originalKey = readKey(event);
    if (!originalKey) {
      console.log(
        "Missing 'key' parameter - queryString: " +
          JSON.stringify(event.queryStringParameters || null) +
          ", body: " +
          (event.body != null ? event.body : "null")
      );
      return errorResponse(400, "Missing 'key' parameter");
    }

    const resizedKey = "resized-" + originalKey;
    try {
      const object = await s3.send(new GetObjectCommand({ Bucket: RESIZED_BUCKET_NAME, Key: resizedKey }));
      const data = await streamToBuffer(object.Body);
      return imageResponse(data, object.ContentType);
    } catch (err) {
      if (err.name === "NoSuchKey" || err.$metadata?.httpStatusCode === 404) {
        console.log("Resized image not found: " + resizedKey + ", attempting on-demand resize");
        try {
          return await createResizedImageOnDemand(originalKey, resizedKey);
        } catch (resizeErr) {
          console.log("Failed to create resized image on-demand: " + resizeErr.message);
          return errorResponse(404, "Image not found");
        }
      }
      console.log("Error fetching resized image: " + err.message);
      return errorResponse(404, "Image not found");
    }
  } catch (err) {
    console.log("Fatal error in handleRequest: " + err.message);
    return errorResponse(500, "Internal server error");
  }
};

exports.resizeImage = resizeImage;
exports.imageTypeOf = imageTypeOf;
exports.readKey = readKey;
