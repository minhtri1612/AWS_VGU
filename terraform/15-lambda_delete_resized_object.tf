# Lambda DeleteResizedObject Function
resource "aws_lambda_function" "delete_resized_object" {
  filename         = "${path.module}/../LambdaDeleteResizedObject/lambda.zip"
  function_name    = "LambdaDeleteResizedObject"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaDeleteResizedObject/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      RESIZED_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
    }
  }
}


