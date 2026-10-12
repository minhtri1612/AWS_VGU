# Lambda GetPhotosDB Function
resource "aws_lambda_function" "get_photos_db" {
  filename         = "${path.module}/../LambdaGetPhotosDB/lambda.zip"
  function_name    = "LambdaGetPhotosDB"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaGetPhotosDB/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = 30 # Database queries might take longer
  memory_size      = var.lambda_memory

  environment {
    variables = {
      RDS_HOSTNAME            = module.database.rds_instance_address
      RDS_PORT                = tostring(module.database.rds_instance_port)
      DB_USER                 = "admin"
      DB_PASSWORD             = var.db_password
      DB_NAME                 = "Cloud26"
      TOKEN_CHECKER_FUNC_NAME = aws_lambda_function.token_checker.function_name
    }
  }
}

