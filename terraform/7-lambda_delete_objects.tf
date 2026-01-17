# Lambda DeleteObjects Function
resource "aws_lambda_function" "delete_objects" {
  filename         = "${path.module}/../LambdaDeleteObjects/target/LambdaDeleteObjects.jar"
  function_name    = "LambdaDeleteObjects"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaDeleteObjects::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaDeleteObjects/target/LambdaDeleteObjects.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      BUCKET_NAME         = aws_s3_bucket.source_bucket.id
      RESIZED_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
      RDS_HOSTNAME        = module.database.rds_instance_address
      RDS_PORT            = tostring(module.database.rds_instance_port)
      DB_USER             = "admin"
      DB_PASSWORD         = var.db_password
      DB_NAME             = "Cloud26"
    }
  }
}

# Lambda Function URL for DeleteObjects
resource "aws_lambda_function_url" "delete_objects" {
  function_name      = aws_lambda_function.delete_objects.function_name
  authorization_type = "NONE"

  cors {
    allow_credentials = false
    allow_origins     = ["*"]
    allow_methods     = ["*"]
    allow_headers     = ["*"]
    expose_headers    = []
    max_age           = 0
  }
}