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

  environment {
    variables = {
      BUCKET_NAME = aws_s3_bucket.source_bucket.id
    }
  }
}
