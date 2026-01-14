# Lambda GetObjects Function
resource "aws_lambda_function" "get_objects" {
  filename         = "${path.module}/../LambdaGetObjects/target/LambdaGetObjects-1.0-SNAPSHOT.jar"
  function_name    = "LambdaGetObjects"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaGetObject::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaGetObjects/target/LambdaGetObjects-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  # Note: Extension Layer requires special permissions or use SDK directly
  # For now, we'll use SDK calls (slower but works without layer permissions)
  # layers = [local.ssm_extension_layer_arn]

  environment {
    variables = {
      BUCKET_NAME            = aws_s3_bucket.source_bucket.id
      TOKEN_CHECKER_FUNC_NAME = aws_lambda_function.token_checker.function_name
      RDS_HOSTNAME           = module.database.rds_instance_address
      RDS_PORT               = tostring(module.database.rds_instance_port)
      DB_USER                = "admin"
      DB_PASSWORD            = var.db_password
      DB_NAME                = "Cloud26"
    }
  }

  depends_on = [
    aws_lambda_function.token_checker
  ]
}

# Lambda Function URL for GetObjects (Download)
resource "aws_lambda_function_url" "get_objects" {
  function_name      = aws_lambda_function.get_objects.function_name
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
