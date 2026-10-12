# Lambda Resize Wrapper Function
resource "aws_lambda_function" "resize_wrapper" {
  filename         = "${path.module}/../LambdaResizeWrapper/lambda.zip"
  function_name    = "LambdaResizeWrapper"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaResizeWrapper/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = 60
  memory_size      = 1024

  environment {
    variables = {
      DEST_BUCKET_NAME = aws_s3_bucket.resized_bucket.id
      BUCKET_NAME      = aws_s3_bucket.source_bucket.id
    }
  }
}

