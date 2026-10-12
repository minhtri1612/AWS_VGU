const mysql = require("mysql2/promise");
const { LambdaClient, InvokeCommand } = require("@aws-sdk/client-lambda");

const lambdaClient = new LambdaClient({ region: "ap-southeast-2" });
const TOKEN_CHECKER_FUNC_NAME = process.env.TOKEN_CHECKER_FUNC_NAME || "LambdaTokenChecker";

const RDS_HOSTNAME = process.env.RDS_HOSTNAME;
const RDS_PORT = process.env.RDS_PORT;
const DB_USER = process.env.DB_USER;
const DB_PASSWORD = process.env.DB_PASSWORD;
const DB_NAME = process.env.DB_NAME;

const CORS_HEADERS = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function jsonResponse(statusCode, body) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: CORS_HEADERS,
    body: typeof body === "string" ? body : JSON.stringify(body),
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
    console.log("Token verification result for email " + email + ": " + isValid);
    return isValid;
  } catch (err) {
    console.log("Error verifying token: " + err.message);
    return false;
  }
}

function readCredentials(event) {
  let requestBody = event.body;
  if (requestBody == null || requestBody === "" || requestBody === "{}") {
    return { token: "", email: "" };
  }

  if (event.isBase64Encoded) {
    try {
      requestBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Decoded base64 request body");
    } catch (err) {
      console.log("Failed to decode base64: " + err.message);
    }
  }
  if (!requestBody.startsWith("{")) {
    try {
      requestBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Decoded base64 request body (fallback)");
    } catch (err) {
      console.log("Failed to decode base64: " + err.message);
    }
  }

  try {
    let bodyJSON = JSON.parse(requestBody);
    if (bodyJSON && typeof bodyJSON.body === "string") {
      bodyJSON = JSON.parse(bodyJSON.body);
    }
    return {
      token: bodyJSON.token || "",
      email: bodyJSON.email || "",
    };
  } catch (err) {
    console.log("Error parsing request body: " + err.message);
    return { token: "", email: "" };
  }
}

exports.handler = async (event) => {
  if (!RDS_HOSTNAME || !RDS_PORT || !DB_USER || !DB_PASSWORD || !DB_NAME) {
    throw new Error("Missing required environment variables: RDS_HOSTNAME, RDS_PORT, DB_USER, DB_PASSWORD, DB_NAME");
  }

  let connection;
  try {
    connection = await mysql.createConnection({
      host: RDS_HOSTNAME,
      port: Number(RDS_PORT),
      user: DB_USER,
      password: DB_PASSWORD,
      database: DB_NAME,
      ssl: { rejectUnauthorized: false },
      connectTimeout: 10000,
    });

    const { token, email } = readCredentials(event);
    let items = [];

    if (!token || !email) {
      console.log("Missing token or email - returning empty list");
    } else if (!(await verifyTokenWithHash(email, token))) {
      console.log("Invalid token - returning empty list");
    } else {
      const [rows] = await connection.query("SELECT * FROM Photos");
      items = rows.map((row) => {
        const item = {
          ID: row.ID,
          Description: row.Description,
          S3Key: row.S3Key,
        };
        if (row.Email != null) {
          item.Email = row.Email;
        }
        return item;
      });
    }

    return jsonResponse(200, items);
  } catch (err) {
    console.log("Database error: " + err.toString());
    return jsonResponse(500, { error: "Database connection error: " + err.message });
  } finally {
    if (connection) {
      await connection.end().catch(() => {});
    }
  }
};

exports.readCredentials = readCredentials;
