const { LambdaClient, InvokeCommand } = require("@aws-sdk/client-lambda");

const lambdaClient = new LambdaClient({ region: "ap-southeast-2" });

const DELETE_FUNC_NAME = process.env.DELETE_FUNC_NAME || "LambdaOrchestrateDeleteHandler";
const GET_FUNC_NAME = process.env.GET_FUNC_NAME || "LambdaGetObjects";
const UPLOAD_FUNC_NAME = process.env.UPLOAD_FUNC_NAME || "LambdaOrchestrateUploadHandler";
const GET_RESIZED_FUNC_NAME = process.env.GET_RESIZED_FUNC_NAME || "LambdaGetResizedImage";
const GET_PHOTOS_DB_FUNC_NAME = process.env.GET_PHOTOS_DB_FUNC_NAME || "LambdaGetPhotosDB";
const ADD_PHOTO_DB_FUNC_NAME = process.env.ADD_PHOTO_DB_FUNC_NAME || "LambdaAddPhotoDB";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function determineAction(event) {
  const httpMethod = event.httpMethod || "";
  const query = event.queryStringParameters;
  let action = "get";
  let queryStringMatched = false;

  if (query) {
    if (Object.prototype.hasOwnProperty.call(query, "action")) {
      action = query.action;
      queryStringMatched = true;
    } else if (Object.prototype.hasOwnProperty.call(query, "format")) {
      const format = query.format;
      if (format === "resized") {
        action = "get_resized";
        queryStringMatched = true;
      } else if (format === "photos" || format === "db") {
        action = "get_photos_db";
        queryStringMatched = true;
      }
    } else if (Object.prototype.hasOwnProperty.call(query, "resized")) {
      action = "get_resized";
      queryStringMatched = true;
    } else if (Object.prototype.hasOwnProperty.call(query, "photos")) {
      action = "get_photos_db";
      queryStringMatched = true;
    }
  }

  if (!queryStringMatched) {
    switch (httpMethod.toUpperCase()) {
      case "POST":
        action = "upload";
        break;
      case "DELETE":
        action = "delete";
        break;
      case "PUT":
      case "GET":
      default:
        action = "get";
        break;
    }
  }

  return action;
}

function functionFor(action) {
  const name = action.toLowerCase();
  if (name === "delete") return DELETE_FUNC_NAME;
  if (name === "upload") return UPLOAD_FUNC_NAME;
  if (name === "get_resized") return GET_RESIZED_FUNC_NAME;
  if (name === "get_photos_db") return GET_PHOTOS_DB_FUNC_NAME;
  if (name === "add_photo_db") return ADD_PHOTO_DB_FUNC_NAME;
  return GET_FUNC_NAME;
}

function buildPayload(event) {
  const lambdaEvent = { httpMethod: event.httpMethod };
  lambdaEvent.body = event.body ? event.body : "{}";
  if (event.headers && Object.keys(event.headers).length > 0) {
    lambdaEvent.headers = event.headers;
  }
  if (event.queryStringParameters) {
    lambdaEvent.queryStringParameters = event.queryStringParameters;
  }
  return JSON.stringify(lambdaEvent);
}

function forwardResponse(responseObject) {
  const headers = {};
  if (responseObject.headers && typeof responseObject.headers === "object") {
    for (const key of Object.keys(responseObject.headers)) {
      const value = responseObject.headers[key];
      if (typeof value !== "string") {
        throw new Error("Header value is not a string");
      }
      headers[key] = value;
    }
  }
  headers["Access-Control-Allow-Origin"] = "*";
  headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS";
  headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization";

  let body;
  if (Object.prototype.hasOwnProperty.call(responseObject, "body")) {
    if (typeof responseObject.body !== "string") {
      throw new Error("Body is not a string");
    }
    body = responseObject.body;
  } else {
    body = JSON.stringify(responseObject);
  }

  const response = {
    statusCode: Object.prototype.hasOwnProperty.call(responseObject, "statusCode") ? responseObject.statusCode : 200,
    headers,
    body,
  };
  if (Object.prototype.hasOwnProperty.call(responseObject, "isBase64Encoded")) {
    response.isBase64Encoded = Boolean(responseObject.isBase64Encoded);
  }
  return response;
}

exports.handler = async (event) => {
  console.log("Invoking LambdaEntryPoint");
  const httpMethod = event.httpMethod;
  if (httpMethod && httpMethod.toUpperCase() === "OPTIONS") {
    return { statusCode: 200, body: "", headers: { ...CORS } };
  }

  const action = determineAction(event);
  const functionName = functionFor(action);
  const payload = buildPayload(event);
  console.log("HTTP Method: " + httpMethod + ", Determined action: " + action);
  console.log("Routing action '" + action + "' to function: " + functionName);
  console.log("Payload being sent to " + functionName + ": " + payload);

  try {
    const invokeResult = await lambdaClient.send(
      new InvokeCommand({
        FunctionName: functionName,
        Payload: Buffer.from(payload),
        InvocationType: "RequestResponse",
      })
    );
    const responseString = Buffer.from(invokeResult.Payload || []).toString("utf8");
    console.log("Raw response from " + functionName + ": " + responseString);
    try {
      return forwardResponse(JSON.parse(responseString));
    } catch (err) {
      console.log("Failed to parse response as JSON, returning as plain text: " + err.message);
      return {
        statusCode: 200,
        isBase64Encoded: false,
        headers: {
          "content-type": "text/plain",
          ...CORS,
        },
        body: responseString,
      };
    }
  } catch (err) {
    console.log("Error calling " + functionName + ": " + err.message);
    return {
      statusCode: 500,
      isBase64Encoded: false,
      headers: {
        "content-type": "application/json",
        ...CORS,
      },
      body: JSON.stringify({ error: err.message }),
    };
  }
};

exports.determineAction = determineAction;
exports.functionFor = functionFor;
exports.buildPayload = buildPayload;
exports.forwardResponse = forwardResponse;
