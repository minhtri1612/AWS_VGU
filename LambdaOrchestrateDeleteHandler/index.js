const mysql = require("mysql2/promise");
const { LambdaClient, InvokeCommand } = require("@aws-sdk/client-lambda");

const lambdaClient = new LambdaClient({ region: "ap-southeast-2" });
const TOKEN_CHECKER_FUNC_NAME = process.env.TOKEN_CHECKER_FUNC_NAME || "LambdaTokenChecker";

const DELETE_OBJECTS_FUNC = process.env.DELETE_OBJECTS_FUNC || "LambdaDeleteObjects";
const DELETE_RESIZED_FUNC = process.env.DELETE_RESIZED_FUNC || "LambdaDeleteResizedObject";
const RDS_HOSTNAME = process.env.RDS_HOSTNAME;
const RDS_PORT = process.env.RDS_PORT;
const DB_USER = process.env.DB_USER;
const DB_PASSWORD = process.env.DB_PASSWORD;
const DB_NAME = process.env.DB_NAME;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Amz-Date, X-Api-Key",
  "Access-Control-Max-Age": "3600",
};

function createErrorResponse(statusCode, errorMessage) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
    body: JSON.stringify({ error: errorMessage }),
  };
}

function createResultsResponse(statusCode, results) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
    body: JSON.stringify(results, null, 4),
  };
}

async function verifyTokenWithHash(email, token) {
  if (!email || !token) {
    console.log("Missing email or token");
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
    console.log("Token verification for " + email + ": isValid=" + isValid);
    return isValid;
  } catch (err) {
    console.log("Error verifying token: " + err.message);
    return false;
  }
}

async function verifyPhotoOwnership(key, email) {
  const connection = await mysql.createConnection({
    host: RDS_HOSTNAME,
    port: Number(RDS_PORT),
    user: DB_USER,
    password: DB_PASSWORD,
    database: DB_NAME,
    ssl: { rejectUnauthorized: false },
    connectTimeout: 10000,
  });
  try {
    const [rows] = await connection.execute(
      "SELECT COUNT(*) as count FROM Photos WHERE S3Key = ? AND Email = ?",
      [key, email]
    );
    if (rows[0] && Number(rows[0].count) > 0) {
      console.log("Ownership verified: " + key + " belongs to " + email);
      return true;
    }
    console.log("Photo " + key + " does not belong to " + email);
    return false;
  } finally {
    await connection.end();
  }
}

async function callLambda(functionName, payload) {
  try {
    const invokeResult = await lambdaClient.send(
      new InvokeCommand({
        FunctionName: functionName,
        Payload: Buffer.from(payload),
        InvocationType: "RequestResponse",
      })
    );
    if (invokeResult.FunctionError) {
      console.log("Lambda function error: " + invokeResult.FunctionError);
      return "Failed: Lambda execution failed: " + invokeResult.FunctionError;
    }
    const jsonResponse = Buffer.from(invokeResult.Payload || []).toString("utf8");
    try {
      const responseObject = JSON.parse(jsonResponse);
      if (typeof responseObject.body === "string") {
        return responseObject.body;
      }
    } catch (err) {
      // If not JSON, return as-is
    }
    return jsonResponse;
  } catch (err) {
    console.log("Error invoking " + functionName + ": " + err.message);
    return "Failed: " + err.message;
  }
}

function parseBody(event) {
  let userRequestBody = event.body;
  if (userRequestBody && userRequestBody !== "{}") {
    if (event.isBase64Encoded) {
      try {
        userRequestBody = Buffer.from(userRequestBody, "base64").toString("utf8");
        console.log("Decoded base64 request body");
      } catch (err) {
        console.log("Failed to decode base64: " + err.message);
      }
    }
    if (!userRequestBody.startsWith("{")) {
      try {
        userRequestBody = Buffer.from(userRequestBody, "base64").toString("utf8");
        console.log("Decoded base64 request body (fallback)");
      } catch (err) {
        console.log("Failed to decode base64: " + err.message);
      }
    }
  }

  let bodyJSON = JSON.parse(userRequestBody || "{}");
  if (bodyJSON && typeof bodyJSON.body === "string" && Object.prototype.hasOwnProperty.call(bodyJSON, "httpMethod")) {
    if (bodyJSON.body && bodyJSON.body !== "{}") {
      bodyJSON = JSON.parse(bodyJSON.body);
    }
  }
  return { bodyJSON, userRequestBody };
}

exports.handler = async (event) => {
  if (!RDS_HOSTNAME || !RDS_PORT || !DB_USER || !DB_PASSWORD || !DB_NAME) {
    throw new Error("Missing required RDS environment variables");
  }

  try {
    if (event.httpMethod && event.httpMethod.toUpperCase() === "OPTIONS") {
      return { statusCode: 200, isBase64Encoded: false, headers: CORS_HEADERS, body: "" };
    }

    let parsed;
    try {
      parsed = parseBody(event);
    } catch (err) {
      console.log("Error parsing request body: " + err.message);
      return createErrorResponse(400, "Invalid JSON in request body: " + err.message);
    }

    const { bodyJSON, userRequestBody } = parsed;
    const key = bodyJSON.key || "";
    if (!key) {
      return createErrorResponse(400, "Missing 'key' field in request body");
    }

    const token = bodyJSON.token || "";
    const email = bodyJSON.email || "";
    if (!token || !email) {
      console.log("Missing token or email");
      return createErrorResponse(403, "Missing token or email");
    }

    if (!(await verifyTokenWithHash(email, token))) {
      console.log("Token verification failed for email: " + email);
      return createErrorResponse(403, "Invalid token");
    }

    let owns;
    try {
      owns = await verifyPhotoOwnership(key, email);
    } catch (err) {
      console.log("Exception during ownership verification: " + err.message);
      return createErrorResponse(500, "Ownership verification error: " + err.message);
    }
    if (!owns) {
      console.log("Ownership verification failed - key: " + key + ", email: " + email);
      return createErrorResponse(403, "You don't have permission to delete this photo");
    }

    console.log("Starting delete workflow for key: " + key);
    const workerPayload = JSON.stringify({
      httpMethod: "DELETE",
      body: userRequestBody || "{}",
      headers: { "Content-Type": "application/json" },
    });
    const results = {};

    console.log("Step 1: Deleting from S3 and DB...");
    const deleteObjectsResult = await callLambda(DELETE_OBJECTS_FUNC, workerPayload);
    results.Activity_1_Original_S3_and_DB = deleteObjectsResult;
    if (
      deleteObjectsResult.includes("Error") ||
      deleteObjectsResult.includes("Failed") ||
      deleteObjectsResult.includes("error")
    ) {
      console.log("Delete from S3/DB failed, aborting workflow");
      results.Activity_2_Resized_S3 = "Skipped (S3/DB delete failed)";
      return createResultsResponse(500, results);
    }

    console.log("Step 2: Deleting resized image...");
    const deleteResizedResult = await callLambda(DELETE_RESIZED_FUNC, workerPayload);
    results.Activity_2_Resized_S3 = deleteResizedResult;
    if (deleteResizedResult.includes("Error") || deleteResizedResult.includes("Failed")) {
      console.log("Resized delete failed (non-critical): " + deleteResizedResult);
    }
    console.log("All delete activities completed");
    return createResultsResponse(200, results);
  } catch (err) {
    console.log("ERROR in delete orchestrator: " + err.message);
    return createErrorResponse(500, "Delete orchestrator failed: " + err.message);
  }
};

exports.parseBody = parseBody;
