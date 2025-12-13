# Lambda Resize Function
resource "aws_lambda_function" "resize" {
  filename         = "${path.module}/../LambdaResize/target/LambdaResize-1.0-SNAPSHOT.jar"
  function_name    = "LambdaResize"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaResize::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaResize/target/LambdaResize-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = 60   # Longer timeout for image processing
  memory_size      = 1024 # More memory for image processing

  environment {
    variables = {
      DEST_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
    }
  }
}

# Lambda permission for S3 to invoke resize function
resource "aws_lambda_permission" "allow_s3_invoke_resize" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.resize.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.source_bucket.arn
}
