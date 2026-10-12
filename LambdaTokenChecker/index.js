const crypto = require("crypto");
const { SSMClient, GetParameterCommand } = require("@aws-sdk/client-ssm");

const ssmClient = new SSMClient({ region: "ap-southeast-2" });

const CORS_HEADERS = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function response(statusCode, body, extraHeaders = {}) {
  const headers = { ...CORS_HEADERS, ...extraHeaders };
  for (const key of Object.keys(headers)) {
    if (headers[key] === undefined) {
      delete headers[key];
    }
  }
  return {
    statusCode,
    isBase64Encoded: false,
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  };
}

function errorResponse(statusCode, message) {
  return response(statusCode, { error: message, valid: false });
}

function generateSecureToken(email, secretKey) {
  return crypto.createHmac("sha256", secretKey).update(email, "utf8").digest("base64");
}

async function getSecretKey() {
  try {
    const result = await ssmClient.send(
      new GetParameterCommand({
        Name: "keytokenhash",
        WithDecryption: true,
      })
    );
    console.log("Successfully retrieved SECRET_KEY from Parameter Store");
    return result.Parameter && result.Parameter.Value;
  } catch (err) {
    console.log("Error retrieving SECRET_KEY from Parameter Store: " + err.message);
    const fallbackKey = process.env.SECRET_KEY;
    if (fallbackKey) {
      console.log("Using SECRET_KEY from environment variable as fallback");
      return fallbackKey;
    }
    throw err;
  }
}

exports.handler = async (event) => {
  const httpMethod = event.httpMethod || "";
  if (httpMethod.toUpperCase() === "OPTIONS") {
    return response(200, "", {
      "Access-Control-Max-Age": "3600",
      "Content-Type": undefined,
    });
  }

  try {
    let body = event.body;
    if (body == null || body === "") {
      return errorResponse(400, "Missing request body");
    }

    let bodyJSON = JSON.parse(body);
    if (bodyJSON && typeof bodyJSON.body === "string") {
      bodyJSON = JSON.parse(bodyJSON.body);
    }

    const email = bodyJSON.email || "";
    const providedToken = bodyJSON.token || "";
    if (!email || !providedToken) {
      console.log("Error: 'email' and 'token' parameters are required");
      return errorResponse(400, "Missing required parameters: email and token");
    }

    const secretKey = await getSecretKey();
    const generatedToken = generateSecureToken(email, secretKey);
    const isValid = generatedToken === providedToken;
    console.log("Token validation result for email " + email + ": " + isValid);

    return response(200, {
      valid: isValid,
      email,
      message: isValid ? "Token is valid" : "Token is invalid",
    });
  } catch (err) {
    console.log("Error processing request: " + err.message);
    return errorResponse(500, "Invalid JSON input: " + err.message);
  }
};

exports.generateSecureToken = generateSecureToken;
