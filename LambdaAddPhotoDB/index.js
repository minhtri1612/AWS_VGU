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

function createResponse(statusCode, message) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: CORS_HEADERS,
    body: JSON.stringify({ message }),
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

function dbConfig(database) {
  const config = {
    host: RDS_HOSTNAME,
    port: Number(RDS_PORT),
    user: DB_USER,
    password: DB_PASSWORD,
    ssl: { rejectUnauthorized: false },
    connectTimeout: 10000,
  };
  if (database) {
    config.database = database;
  }
  return config;
}

async function ensureSchema() {
  const connection = await mysql.createConnection(dbConfig());
  try {
    await connection.query("CREATE DATABASE IF NOT EXISTS " + DB_NAME);
    await connection.query("USE " + DB_NAME);
    await connection.query(
      "CREATE TABLE IF NOT EXISTS Photos (" +
        "ID INT NOT NULL AUTO_INCREMENT PRIMARY KEY, " +
        "Description VARCHAR(255), " +
        "S3Key VARCHAR(512), " +
        "Email VARCHAR(255))"
    );
  } finally {
    await connection.end();
  }
}

async function insertPhoto(description, originalFileName, email) {
  const write = async () => {
    const connection = await mysql.createConnection(dbConfig(DB_NAME));
    try {
      await connection.execute("INSERT INTO Photos (Description, S3Key, Email) VALUES (?, ?, ?)", [
        description,
        originalFileName,
        email,
      ]);
      console.log("Inserted row: " + description + " | " + originalFileName + " | Email: " + email);
    } finally {
      await connection.end();
    }
  };

  try {
    await write();
  } catch (err) {
    if (err.code !== "ER_BAD_DB_ERROR" && err.code !== "ER_NO_SUCH_TABLE") {
      throw err;
    }
    console.log("Schema missing, creating Cloud26.Photos");
    await ensureSchema();
    await write();
  }
}

function parseBody(event) {
  let requestBody = event.body;
  console.log("Received body length: " + (requestBody != null ? requestBody.length : 0));
  if (requestBody == null || requestBody === "" || requestBody === "{}") {
    return { error: createResponse(400, "Error: Request body is empty") };
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
      requestBody = Buffer.from(requestBody, "base64").toString("utf8");
      console.log("Successfully decoded base64, now parsing JSON");
      bodyJSON = JSON.parse(requestBody);
    } catch (decodeErr) {
      console.log("Failed to decode base64 or parse JSON: " + decodeErr.message);
      return { error: createResponse(400, "Error: Invalid JSON in request body: " + err.message) };
    }
  }

  if (bodyJSON && Object.prototype.hasOwnProperty.call(bodyJSON, "body") && Object.prototype.hasOwnProperty.call(bodyJSON, "httpMethod")) {
    const actualBody = bodyJSON.body;
    console.log("Extracted actual body from wrapper");
    if (actualBody && actualBody !== "{}") {
      bodyJSON = typeof actualBody === "string" ? JSON.parse(actualBody) : actualBody;
    } else {
      return { error: createResponse(400, "Error: Empty body in wrapper") };
    }
  }

  return { bodyJSON };
}

exports.handler = async (event) => {
  if (!RDS_HOSTNAME || !RDS_PORT || !DB_USER || !DB_PASSWORD || !DB_NAME) {
    throw new Error("Missing required environment variables: RDS_HOSTNAME, RDS_PORT, DB_USER, DB_PASSWORD, DB_NAME");
  }

  try {
    const parsed = parseBody(event);
    if (parsed.error) return parsed.error;
    const bodyJSON = parsed.bodyJSON;

    const originalFileName = bodyJSON.key || "";
    const description = bodyJSON.description || "Uploaded photo";
    const token = bodyJSON.token || "";
    const email = bodyJSON.email || "";

    if (!originalFileName) {
      return createResponse(400, "Error: Missing 'key' field (filename)");
    }
    if (!token || !email) {
      return createResponse(403, "Error: Token and email required");
    }
    if (!(await verifyTokenWithHash(email, token))) {
      return createResponse(403, "Error: Invalid token");
    }

    console.log("Processing DB insert for file: " + originalFileName + ", email from token: " + email);

    await insertPhoto(description, originalFileName, email);

    return createResponse(200, "Success: Photo added to database");
  } catch (err) {
    console.log("Error: " + err.toString());
    return createResponse(500, "Error adding to DB: " + err.message);
  }
};

exports.parseBody = parseBody;
