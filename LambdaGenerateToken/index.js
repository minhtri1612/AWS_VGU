const crypto = require("crypto");
const { LambdaClient, InvokeCommand } = require("@aws-sdk/client-lambda");
const { SESClient, SendEmailCommand } = require("@aws-sdk/client-ses");
const { SSMClient, GetParameterCommand } = require("@aws-sdk/client-ssm");

const SECRET_KEY_PARAM_NAME = "keytokenhash";
const REGION = "ap-southeast-2";
const ssmClient = new SSMClient({ region: REGION });
const sesClient = new SESClient({ region: REGION });
const lambdaClient = new LambdaClient({ region: REGION });
const FROM_EMAIL = process.env.FROM_EMAIL;
const TOKEN_CHECKER_FUNC_NAME = process.env.TOKEN_CHECKER_FUNC_NAME || "LambdaTokenChecker";

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
        Name: SECRET_KEY_PARAM_NAME,
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
    return null;
  }
}

function parseBody(event) {
  let body = event.body;
  if (body == null || body === "") {
    return { error: errorResponse(400, "Missing request body") };
  }

  if (event.isBase64Encoded) {
    try {
      body = Buffer.from(body, "base64").toString("utf8");
      console.log("Decoded base64 request body (flag was set)");
    } catch (err) {
      console.log("Failed to decode base64 body: " + err.message);
    }
  }

  let bodyJSON;
  try {
    bodyJSON = JSON.parse(body);
  } catch (err) {
    console.log("Failed to parse as JSON, trying base64 decode: " + err.message);
    try {
      const decodedBody = Buffer.from(body, "base64").toString("utf8");
      bodyJSON = JSON.parse(decodedBody);
    } catch (decodeErr) {
      console.log("Failed to decode base64 or parse JSON: " + decodeErr.message);
      return { error: errorResponse(400, "Error: Invalid JSON in request body: " + err.message) };
    }
  }

  if (bodyJSON && Object.prototype.hasOwnProperty.call(bodyJSON, "body") && Object.prototype.hasOwnProperty.call(bodyJSON, "httpMethod")) {
    const actualBody = bodyJSON.body;
    console.log("Extracted actual body from wrapper");
    if (actualBody && actualBody !== "{}") {
      bodyJSON = typeof actualBody === "string" ? JSON.parse(actualBody) : actualBody;
    } else {
      return { error: errorResponse(400, "Error: Empty body in wrapper") };
    }
  }

  return { bodyJSON };
}

exports.handler = async (event) => {
  if (event.httpMethod && event.httpMethod.toUpperCase() === "OPTIONS") {
    return response(200, "", { "Access-Control-Max-Age": "3600", "Content-Type": undefined });
  }

  try {
    const parsed = parseBody(event);
    if (parsed.error) {
      return parsed.error;
    }

    const bodyJSON = parsed.bodyJSON || {};
    const action = bodyJSON.action || "";
    const email = bodyJSON.email || "";
    const providedToken = bodyJSON.token || "";

    if (action === "request_token") {
      if (!email) {
        return errorResponse(400, "Missing email parameter");
      }
      const secretKey = await getSecretKey();
      if (!secretKey) {
        return errorResponse(500, "Error: Failed to retrieve SECRET_KEY from Parameter Store");
      }
      if (!FROM_EMAIL) {
        return errorResponse(500, "Error: FROM_EMAIL is not configured");
      }
      const token = generateSecureToken(email, secretKey);
      console.log("Generated token for email: " + email);
      await sesClient.send(
        new SendEmailCommand({
          Source: FROM_EMAIL,
          Destination: { ToAddresses: [email] },
          Message: {
            Subject: { Data: "Your photo gallery login token", Charset: "UTF-8" },
            Body: {
              Text: {
                Data: "Your login token is:\n\n" + token + "\n\nPaste this token into the gallery login form.",
                Charset: "UTF-8",
              },
            },
          },
        })
      );
      console.log("Sent token email to: " + email);
      return response(200, {
        message: "Token sent successfully",
        email,
      });
    }

    if (action === "verify_token") {
      if (!email || !providedToken) {
        return errorResponse(400, "Missing email or token parameter");
      }
      let isValid = false;
      try {
        const result = await lambdaClient.send(
          new InvokeCommand({
            FunctionName: TOKEN_CHECKER_FUNC_NAME,
            Payload: Buffer.from(JSON.stringify({
              httpMethod: "POST",
              body: JSON.stringify({ email, token: providedToken }),
            })),
          })
        );
        const parsed = JSON.parse(Buffer.from(result.Payload || []).toString("utf8"));
        const checkerBody = typeof parsed.body === "string" ? JSON.parse(parsed.body) : parsed;
        isValid = checkerBody.valid === true;
      } catch (err) {
        console.log("Error calling LambdaTokenChecker: " + err.message);
        return errorResponse(500, "Error verifying token");
      }
      console.log("Token validation result for email " + email + ": " + isValid);
      return response(200, {
        valid: isValid,
        email,
        message: isValid ? "Token is valid" : "Token is invalid",
      });
    }

    return errorResponse(400, "Invalid action. Use 'request_token' or 'verify_token'");
  } catch (err) {
    console.log("Error processing request: " + err.message);
    return errorResponse(500, "Invalid JSON input: " + err.message);
  }
};

exports.generateSecureToken = generateSecureToken;
