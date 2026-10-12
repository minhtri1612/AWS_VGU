# Lambda UploadObjects Function
resource "aws_lambda_function" "upload_objects" {
  filename         = "${path.module}/../LambdaUploadObjects/lambda.zip"
  function_name    = "LambdaUploadObjects"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaUploadObjects/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      BUCKET_NAME = aws_s3_bucket.source_bucket.id
    }
  }
}
