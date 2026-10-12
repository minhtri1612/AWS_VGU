const { S3Client, DeleteObjectCommand, DeleteObjectsCommand } = require("@aws-sdk/client-s3");
const mysql = require("mysql2/promise");

const s3Client = new S3Client({ region: "ap-southeast-2" });
const SOURCE_BUCKET_NAME = process.env.BUCKET_NAME;

function createResponse(statusCode, body) {
  return {
    statusCode,
    isBase64Encoded: false,
    headers: { "Content-Type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  };
}

function dbConfig() {
  return {
    host: process.env.RDS_HOSTNAME,
    port: Number(process.env.RDS_PORT),
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    ssl: { rejectUnauthorized: false },
    connectTimeout: 10000,
  };
}

function requireDbEnv() {
  if (!process.env.RDS_HOSTNAME || !process.env.RDS_PORT || !process.env.DB_USER || !process.env.DB_PASSWORD || !process.env.DB_NAME) {
    throw new Error("Missing RDS environment variables");
  }
}

async function withDb(fn) {
  requireDbEnv();
  const connection = await mysql.createConnection(dbConfig());
  try {
    return await fn(connection);
  } finally {
    await connection.end();
  }
}

async function verifyPhotoOwnership(keys, email) {
  try {
    return await withDb(async (connection) => {
      for (const key of keys) {
        const [rows] = await connection.execute(
          "SELECT COUNT(*) as count FROM Photos WHERE S3Key = ? AND Email = ?",
          [key, email]
        );
        if (!rows[0] || Number(rows[0].count) === 0) {
          console.log("Photo " + key + " does not belong to " + email);
          return false;
        }
      }
      return true;
    });
  } catch (err) {
    console.log("Error verifying ownership: " + err.message);
    return false;
  }
}

async function deleteFromDatabase(key) {
  await withDb(async (connection) => {
    const [result] = await connection.execute("DELETE FROM Photos WHERE S3Key = ?", [key]);
    console.log("Database delete result: " + result.affectedRows + " row(s) deleted");
  });
}

async function deleteFromDatabaseBatch(keys) {
  await withDb(async (connection) => {
    const placeholders = keys.map(() => "?").join(",");
    const [result] = await connection.execute(
      "DELETE FROM Photos WHERE S3Key IN (" + placeholders + ")",
      keys
    );
    console.log("Database batch delete result: " + result.affectedRows + " row(s) deleted");
  });
}

function parseRequestBody(requestBody) {
  if (requestBody && !requestBody.startsWith("{")) {
    try {
      const decodedBody = Buffer.from(requestBody, "base64").toString("utf8");
      if (decodedBody.trim().startsWith("{")) {
        requestBody = decodedBody;
        console.log("Successfully decoded base64 request body");
      }
    } catch (err) {
      console.log("Body is not base64 encoded, using as-is: " + err.message);
    }
  }

  if (requestBody && requestBody.startsWith("{")) {
    try {
      const testJSON = JSON.parse(requestBody);
      if (testJSON && typeof testJSON.body === "string" && Object.prototype.hasOwnProperty.call(testJSON, "httpMethod")) {
        console.log("Extracted body from entry point wrapper: " + testJSON.body);
        requestBody = testJSON.body;
      }
    } catch (err) {
      console.log("Failed to parse wrapped request: " + err.message);
    }
  }
  return requestBody;
}

exports.handler = async (request) => {
  if (!SOURCE_BUCKET_NAME) {
    throw new Error("Missing required environment variable: BUCKET_NAME");
  }

  let requestBody = request.body;
  console.log("Raw request body: " + requestBody);
  requestBody = parseRequestBody(requestBody);

  if (requestBody == null || requestBody.trim() === "") {
    console.log("Request body is null or empty");
    return createResponse(400, { error: "Request body is required" });
  }
  console.log("Final request body to parse: " + requestBody);

  let bodyJSON;
  try {
    bodyJSON = JSON.parse(requestBody);
  } catch (err) {
    console.log("Failed to parse JSON: " + err.message);
    return createResponse(400, { error: "Invalid JSON: " + err.message });
  }

  let keys = [];
  if (Object.prototype.hasOwnProperty.call(bodyJSON, "key")) {
    keys = [bodyJSON.key];
  } else if (Array.isArray(bodyJSON.keys)) {
    keys = bodyJSON.keys;
  } else {
    return createResponse(400, { error: "Missing 'key' or 'keys' field" });
  }

  const email = bodyJSON.email || "";
  if (!email) {
    return createResponse(400, { error: "Missing 'email' field" });
  }
  if (!(await verifyPhotoOwnership(keys, email))) {
    return createResponse(403, { error: "You don't have permission to delete these photos" });
  }

  try {
    if (keys.length === 1) {
      const key = keys[0];
      await s3Client.send(new DeleteObjectCommand({ Bucket: SOURCE_BUCKET_NAME, Key: key }));
      console.log("Deleted from source S3: " + key);
      try {
        await deleteFromDatabase(key);
        console.log("Deleted from database: " + key);
      } catch (err) {
        console.log("Could not delete from database: " + err.message);
      }
      return createResponse(200, {
        deleted: [key],
        message: "File deleted successfully from S3 and database",
      });
    }

    const deletedResponse = await s3Client.send(
      new DeleteObjectsCommand({
        Bucket: SOURCE_BUCKET_NAME,
        Delete: { Objects: keys.map((key) => ({ Key: key })) },
      })
    );
    const deleted = (deletedResponse.Deleted || []).map((item) => item.Key);
    console.log("Deleted from source S3: " + deleted.length + " files");
    try {
      await deleteFromDatabaseBatch(keys);
      console.log("Deleted from database: " + keys.length + " records");
    } catch (err) {
      console.log("Could not delete from database: " + err.message);
    }
    return createResponse(200, {
      deleted,
      message: "Files deleted successfully from S3 and database",
    });
  } catch (err) {
    console.log("Error: " + err.message);
    const label = err.$metadata ? "S3Exception: " : "Exception: ";
    return createResponse(500, { error: label + err.message });
  }
};

exports.parseRequestBody = parseRequestBody;
