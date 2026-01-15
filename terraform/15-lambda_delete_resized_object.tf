# Lambda DeleteResizedObject Function
resource "aws_lambda_function" "delete_resized_object" {
  filename         = "${path.module}/../LambdaDeleteResizedObject/target/LambdaDeleteResizedObject-1.0-SNAPSHOT.jar"
  function_name    = "LambdaDeleteResizedObject"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaDeleteResizedObject::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaDeleteResizedObject/target/LambdaDeleteResizedObject-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      RESIZED_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
    }
  }
}

# NOTE: S3 bucket notification is now defined in 2-s3.tf (merged with resize notification)
# This is because S3 only allows ONE notification configuration per bucket


# Lambda permission for S3 to invoke delete resized function
resource "aws_lambda_permission" "allow_s3_invoke_delete_resized" {
  statement_id  = "AllowS3InvokeDeleteResized"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.delete_resized_object.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.source_bucket.arn
}

