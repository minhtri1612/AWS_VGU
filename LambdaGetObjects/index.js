const { S3Client, HeadObjectCommand, GetObjectCommand, ListObjectsV2Command } = require("@aws-sdk/client-s3");
const { LambdaClient, InvokeCommand } = require("@aws-sdk/client-lambda");

const REGION = "ap-southeast-2";
const s3Client = new S3Client({ region: REGION });
const lambdaClient = new LambdaClient({ region: REGION });
const TOKEN_CHECKER_FUNC_NAME = process.env.TOKEN_CHECKER_FUNC_NAME || "LambdaTokenChecker";
const BUCKET_NAME = process.env.BUCKET_NAME;
const MAX_SIZE = 10 * 1024 * 1024;

function corsHeaders(contentType) {
  return {
    "Content-Type": contentType,
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
  };
}

function errorResponse(statusCode, message) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: corsHeaders("application/json"),
    body: JSON.stringify({ error: message }),
  };
}

function headerValue(headers, name) {
  if (!headers) return null;
  const found = Object.keys(headers).find((key) => key.toLowerCase() === name.toLowerCase());
  return found ? headers[found] : null;
}

function mimeTypeFor(key) {
  const ext = key.includes(".") ? key.split(".").pop().toLowerCase() : "";
  if (ext === "png") return "image/png";
  if (ext === "html") return "text/html";
  if (ext === "jpg" || ext === "jpeg") return "image/jpeg";
  if (ext === "txt") return "text/plain";
  return "application/octet-stream";
}

async function tokenIsValid(email, token) {
  if (!email || !token) {
    return false;
  }
  try {
    const result = await lambdaClient.send(
      new InvokeCommand({
        FunctionName: TOKEN_CHECKER_FUNC_NAME,
        Payload: Buffer.from(JSON.stringify({ httpMethod: "POST", body: JSON.stringify({ email, token }) })),
      })
    );
    const parsed = JSON.parse(Buffer.from(result.Payload || []).toString("utf8"));
    const body = typeof parsed.body === "string" ? JSON.parse(parsed.body) : parsed;
    const isValid = body.valid === true;
    console.log("Token validation result for email " + email + ": " + isValid);
    return isValid;
  } catch (err) {
    console.log("Error verifying token: " + err.message);
    return false;
  }
}

async function streamToBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function getSpecificObject(key) {
  let mimeType = "application/octet-stream";
  let body = "";
  let isBase64Encoded = true;
  let statusCode = 200;

  try {
    const head = await s3Client.send(new HeadObjectCommand({ Bucket: BUCKET_NAME, Key: key }));
    const objectSize = Number(head.ContentLength || 0);
    if (objectSize < MAX_SIZE) {
      mimeType = mimeTypeFor(key);
      const object = await s3Client.send(new GetObjectCommand({ Bucket: BUCKET_NAME, Key: key }));
      const buffer = await streamToBuffer(object.Body);
      if (mimeType.startsWith("text/html") || mimeType.startsWith("text/plain")) {
        body = buffer.toString("utf8");
        isBase64Encoded = false;
      } else {
        body = buffer.toString("base64");
        isBase64Encoded = true;
      }
    } else {
      console.log("File too large: " + objectSize);
      statusCode = 413;
    }
  } catch (err) {
    if (err.$metadata) {
      console.log("S3 Error: " + err.message);
      statusCode = 404;
    } else {
      console.log("IO Error: " + err.message);
      statusCode = 500;
    }
  }

  return {
    statusCode,
    body,
    isBase64Encoded,
    headers: { "Content-Type": mimeType },
  };
}

function skipKey(key) {
  return key === "index.html" || key.startsWith("test") || key.startsWith("warmup") || key === "mqtt3.png";
}

async function listObjects() {
  try {
    const listed = await s3Client.send(new ListObjectsV2Command({ Bucket: BUCKET_NAME }));
    const objects = (listed.Contents || [])
      .filter((obj) => obj.Key && !skipKey(obj.Key))
      .map((obj) => ({
        key: obj.Key,
        size: obj.Size,
        lastModified: obj.LastModified ? new Date(obj.LastModified).toISOString() : "",
      }));
    return {
      statusCode: 200,
      isBase64Encoded: false,
      headers: corsHeaders("application/json"),
      body: JSON.stringify(objects),
    };
  } catch (err) {
    console.log("S3 Error listing objects: " + err.message);
    return {
      statusCode: 500,
      isBase64Encoded: false,
      headers: { "Content-Type": "application/json" },
      body: "[]",
    };
  }
}

exports.handler = async (event) => {
  if (!BUCKET_NAME) {
    throw new Error("Missing required environment variable: BUCKET_NAME");
  }

  let requestBody = event.body;
  console.log("Raw request body: " + requestBody);

  const acceptHeader = headerValue(event.headers, "Accept");
  const contentTypeHeader = headerValue(event.headers, "Content-Type");
  const formatParam = event.queryStringParameters && event.queryStringParameters.format;
  console.log("Accept header: " + acceptHeader);
  console.log("Content-Type header: " + contentTypeHeader);
  console.log("Format parameter: " + formatParam);

  if (requestBody == null || requestBody.trim() === "" || requestBody === "{}") {
    if (formatParam === "json") {
      console.log("format=json parameter detected, returning list of objects");
      return listObjects();
    }
    if (contentTypeHeader && contentTypeHeader.toLowerCase().includes("application/json")) {
      console.log("Content-Type: application/json detected, returning list of objects");
      return listObjects();
    }
    if (acceptHeader && acceptHeader.toLowerCase().includes("text/html")) {
      console.log("Accept: text/html detected, returning index.html");
      return getSpecificObject("index.html");
    }
    console.log("No clear indication, defaulting to index.html for browser");
    return getSpecificObject("index.html");
  }

  if (!requestBody.startsWith("{")) {
    try {
      requestBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Decoded body: " + requestBody);
    } catch (err) {
      console.log("Failed to decode base64: " + err.message);
    }
  }

  let bodyJSON;
  try {
    bodyJSON = JSON.parse(requestBody);
  } catch (err) {
    return errorResponse(400, "Invalid JSON input: " + err.message);
  }

  const key = Object.prototype.hasOwnProperty.call(bodyJSON, "key") ? bodyJSON.key : "index.html";
  if (key !== "index.html") {
    const token = bodyJSON.token || "";
    const email = bodyJSON.email || "";
    if (!token || !email) {
      return errorResponse(403, "Missing token or email");
    }
    if (!(await tokenIsValid(email, token))) {
      return errorResponse(403, "Invalid token");
    }
    console.log("Token verified for download: " + email + ", key: " + key);
  }

  return getSpecificObject(key);
};

exports.mimeTypeFor = mimeTypeFor;
exports.skipKey = skipKey;
