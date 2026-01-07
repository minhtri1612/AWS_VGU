# Lambda AddPhotoDB Function
resource "aws_lambda_function" "add_photo_db" {
  filename         = "${path.module}/../LambdaAddPhotoDB/target/LambdaAddPhotoDB-1.0-SNAPSHOT.jar"
  function_name    = "LambdaAddPhotoDB"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaAddPhotoDB::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaAddPhotoDB/target/LambdaAddPhotoDB-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = 30  # Database operations might take longer
  memory_size      = var.lambda_memory

  environment {
    variables = {
      RDS_HOSTNAME = "project1.cbmawawwgw2n.ap-southeast-2.rds.amazonaws.com"
      RDS_PORT     = "3306"
      DB_USER      = "admin"
      DB_PASSWORD  = var.db_password
      DB_NAME      = "Cloud26"
    }
  }
}

