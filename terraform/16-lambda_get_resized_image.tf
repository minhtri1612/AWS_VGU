# Lambda GetResizedImage Function
resource "aws_lambda_function" "get_resized_image" {
  filename         = "${path.module}/../LambdaGetResizedImage/lambda.zip"
  function_name    = "LambdaGetResizedImage"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaGetResizedImage/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      RESIZED_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
      SOURCE_BUCKET_NAME  = aws_s3_bucket.source_bucket.id
    }
  }
}

# Lambda Function URL for GetResizedImage (Thumbnail)
resource "aws_lambda_function_url" "get_resized_image" {
  function_name      = aws_lambda_function.get_resized_image.function_name
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

