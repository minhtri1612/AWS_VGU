# Lambda UploadObjects Function
resource "aws_lambda_function" "upload_objects" {
  filename         = "${path.module}/../LambdaUploadObjects/target/LambdaUploadObjects-1.0-SNAPSHOT.jar"
  function_name    = "LambdaUploadObjects"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaUploadObjects::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaUploadObjects/target/LambdaUploadObjects-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      BUCKET_NAME = aws_s3_bucket.source_bucket.id
    }
  }
}
