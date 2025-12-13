# Lambda Resize Wrapper Function
resource "aws_lambda_function" "resize_wrapper" {
  filename         = "${path.module}/../LambdaResizeWrapper/target/LambdaResizeWrapper-1.0-SNAPSHOT.jar"
  function_name    = "LambdaResizeWrapper"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaResizeWrapper::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaResizeWrapper/target/LambdaResizeWrapper-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = 60   # Longer timeout for image processing
  memory_size      = 1024 # More memory for image processing

  environment {
    variables = {
      DEST_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
      BUCKET_NAME      = aws_s3_bucket.source_bucket.id
    }
  }
}

