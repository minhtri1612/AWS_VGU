# Lambda GetObjects Function
resource "aws_lambda_function" "get_objects" {
  filename         = "${path.module}/../LambdaGetObjects/lambda.zip"
  function_name    = "LambdaGetObjects"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaGetObjects/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      BUCKET_NAME             = aws_s3_bucket.source_bucket.id
      TOKEN_CHECKER_FUNC_NAME = aws_lambda_function.token_checker.function_name
    }
  }
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
