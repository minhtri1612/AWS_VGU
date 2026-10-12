const { LambdaClient, InvokeCommand } = require("@aws-sdk/client-lambda");
const { SFNClient, StartExecutionCommand, DescribeExecutionCommand } = require("@aws-sdk/client-sfn");

const REGION = "ap-southeast-2";
const lambdaClient = new LambdaClient({ region: REGION });
const sfnClient = new SFNClient({ region: REGION });
const TOKEN_CHECKER_FUNC_NAME = process.env.TOKEN_CHECKER_FUNC_NAME || "LambdaTokenChecker";

const ADD_PHOTO_DB_FUNC_NAME = process.env.ADD_PHOTO_DB_FUNC_NAME || "LambdaAddPhotoDB";
const UPLOAD_OBJECTS_FUNC_NAME = process.env.UPLOAD_OBJECTS_FUNC_NAME || "LambdaUploadObjects";
const RESIZE_WRAPPER_FUNC_NAME = process.env.RESIZE_WRAPPER_FUNC_NAME || "LambdaResizeWrapper";
const STATE_MACHINE_ARN = process.env.STATE_MACHINE_ARN || "";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Amz-Date, X-Api-Key",
  "Access-Control-Max-Age": "3600",
};

function jsonResponse(statusCode, body, extraHeaders = {}) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      ...extraHeaders,
    },
    body: typeof body === "string" ? body : JSON.stringify(body),
  };
}

function createErrorResponse(statusCode, errorMessage) {
  return jsonResponse(statusCode, { error: errorMessage });
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
    console.log("Token verification result for email " + email + ": " + isValid);
    return isValid;
  } catch (err) {
    console.log("Error verifying token: " + err.message);
    return false;
  }
}

function readCredentials(userRequestBody) {
  try {
    const bodyJSON = JSON.parse(userRequestBody || "{}");
    return {
      token: bodyJSON.token == null ? null : String(bodyJSON.token),
      email: bodyJSON.email == null ? null : String(bodyJSON.email),
      key: bodyJSON.key || "",
      content: bodyJSON.content || "",
    };
  } catch (err) {
    console.log("Error parsing request body: " + err.message);
    return { token: null, email: null, key: "", content: "" };
  }
}

function maybeDecodeBase64(userRequestBody) {
  if (userRequestBody && !userRequestBody.startsWith("{")) {
    try {
      const decoded = Buffer.from(userRequestBody, "base64").toString("utf8");
      console.log("Decoded base64 request body");
      return decoded;
    } catch (err) {
      console.log("Failed to decode base64: " + err.message);
    }
  }
  return userRequestBody;
}

async function callLambda(functionName, payload) {
  try {
    const result = await lambdaClient.send(
      new InvokeCommand({
        FunctionName: functionName,
        Payload: Buffer.from(payload),
        InvocationType: "RequestResponse",
      })
    );
    const jsonResponse = Buffer.from(result.Payload || []).toString("utf8");
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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForExecution(executionArn, timeoutSeconds) {
  const start = Date.now();
  const timeoutMillis = timeoutSeconds * 1000;
  while (Date.now() - start < timeoutMillis) {
    try {
      const execution = await sfnClient.send(new DescribeExecutionCommand({ executionArn }));
      const status = execution.status;
      if (status === "SUCCEEDED" || status === "FAILED" || status === "TIMED_OUT" || status === "ABORTED") {
        console.log("Step Functions execution completed with status: " + status);
        return status;
      }
      await sleep(500);
    } catch (err) {
      console.log("Error checking execution status: " + err.message);
      return "UNKNOWN";
    }
  }
  console.log("Step Functions execution timed out after " + timeoutSeconds + " seconds");
  return "TIMED_OUT";
}

async function getExecutionOutput(executionArn) {
  try {
    const execution = await sfnClient.send(new DescribeExecutionCommand({ executionArn }));
    return execution.output || "{}";
  } catch (err) {
    console.log("Error getting execution output: " + err.message);
    return "{\"error\":\"Could not retrieve output\"}";
  }
}

async function handleUploadOperationDirect(userRequestBody) {
  const results = {};
  try {
    userRequestBody = maybeDecodeBase64(userRequestBody);
    const creds = readCredentials(userRequestBody);
    if (creds.token == null || creds.email == null) {
      return createErrorResponse(403, "Missing token or email");
    }
    if (!(await verifyTokenWithHash(creds.email, creds.token))) {
      return createErrorResponse(403, "Invalid token");
    }
    if (!creds.key || !creds.content) {
      return createErrorResponse(400, "Missing 'key' or 'content' field");
    }

    console.log("Starting sequential upload workflow for key: " + creds.key);
    const downstreamPayload = JSON.stringify({
      httpMethod: "POST",
      body: userRequestBody || "{}",
      headers: { "Content-Type": "application/json" },
    });

    console.log("Step 1: Inserting row into database...");
    const dbResult = await callLambda(ADD_PHOTO_DB_FUNC_NAME, downstreamPayload);
    results.Activity_1_Database = dbResult;
    if (dbResult.includes("Error") || dbResult.includes("Failed")) {
      console.log("Database insert failed, aborting workflow");
      results.Activity_2_Original_S3 = "Skipped (DB insert failed)";
      results.Activity_3_Resize_S3 = "Skipped (DB insert failed)";
      return jsonResponse(500, JSON.stringify(results, null, 4));
    }

    console.log("Step 2: Uploading original to S3...");
    const uploadResult = await callLambda(UPLOAD_OBJECTS_FUNC_NAME, downstreamPayload);
    results.Activity_2_Original_S3 = uploadResult;
    if (uploadResult.includes("Error") || uploadResult.includes("Failed")) {
      console.log("Original upload failed, aborting resize workflow");
      results.Activity_3_Resize_S3 = "Skipped (Original upload failed)";
      return jsonResponse(500, JSON.stringify(results, null, 4));
    }

    console.log("Step 3: Resizing and uploading resized image...");
    const resizeResult = await callLambda(RESIZE_WRAPPER_FUNC_NAME, downstreamPayload);
    results.Activity_3_Resize_S3 = resizeResult;
    if (resizeResult.includes("Error") || resizeResult.includes("Failed")) {
      console.log("Resize upload failed (non-critical): " + resizeResult);
    }
    console.log("All upload activities completed");
    return jsonResponse(200, JSON.stringify(results, null, 4));
  } catch (err) {
    console.log("Error in direct upload orchestrator: " + err.message);
    results.error = "Upload orchestrator failed: " + err.message;
    return jsonResponse(500, JSON.stringify(results, null, 4));
  }
}

async function handleUploadOperation(userRequestBody) {
  try {
    userRequestBody = maybeDecodeBase64(userRequestBody);
    const creds = readCredentials(userRequestBody);
    if (creds.token == null || creds.email == null) {
      return createErrorResponse(403, "Missing token or email");
    }
    if (!(await verifyTokenWithHash(creds.email, creds.token))) {
      return createErrorResponse(403, "Invalid token");
    }
    console.log("Token verified, email from token: " + creds.email);

    if (!STATE_MACHINE_ARN) {
      console.log("STATE_MACHINE_ARN not configured, falling back to direct Lambda calls");
      return handleUploadOperationDirect(userRequestBody);
    }

    console.log("Starting Step Functions execution for upload workflow...");
    let executionArn;
    try {
      const started = await sfnClient.send(
        new StartExecutionCommand({
          stateMachineArn: STATE_MACHINE_ARN,
          input: JSON.stringify({ body: userRequestBody || "{}" }),
        })
      );
      executionArn = started.executionArn;
    } catch (err) {
      console.log("Error starting Step Functions: " + err.message);
      console.log("Falling back to direct Lambda calls...");
      return handleUploadOperationDirect(userRequestBody);
    }

    console.log("Step Functions execution started: " + executionArn);
    const finalStatus = await waitForExecution(executionArn, 300);
    if (finalStatus === "SUCCEEDED") {
      const output = await getExecutionOutput(executionArn);
      return jsonResponse(
        200,
        JSON.stringify(
          {
            executionArn,
            status: "SUCCEEDED",
            output,
            message: "Upload workflow completed successfully via Step Functions",
          },
          null,
          4
        )
      );
    }
    return createErrorResponse(500, "Step Functions execution failed with status: " + finalStatus);
  } catch (err) {
    console.log("Error in Step Functions upload: " + err.message);
    return createErrorResponse(500, "Upload orchestrator failed: " + err.message);
  }
}

exports.handler = async (event) => {
  try {
    if (event == null) {
      console.log("ERROR: Received null event");
      return {
        statusCode: 400,
        isBase64Encoded: false,
        headers: CORS_HEADERS,
        body: "{\"error\":\"Invalid request: event is null\"}",
      };
    }

    if (event.httpMethod && event.httpMethod.toUpperCase() === "OPTIONS") {
      return { statusCode: 200, isBase64Encoded: false, headers: CORS_HEADERS, body: "" };
    }

    const httpMethod = (event.httpMethod || "POST").toUpperCase();
    const userRequestBody = event.body;
    console.log("HTTP Method: " + httpMethod);
    console.log("Original request body length: " + (userRequestBody != null ? userRequestBody.length : 0));

    const response = await handleUploadOperation(userRequestBody);
    response.headers = { ...(response.headers || {}), ...CORS_HEADERS };
    return response;
  } catch (err) {
    console.log("ERROR in handleRequest: " + err.message);
    return {
      statusCode: 500,
      isBase64Encoded: false,
      headers: CORS_HEADERS,
      body: JSON.stringify({ error: "Internal server error: " + err.message }),
    };
  }
};

exports.readCredentials = readCredentials;
