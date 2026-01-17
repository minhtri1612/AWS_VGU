# Lambda Orchestrate Delete Handler Function
resource "aws_lambda_function" "orchestrate_delete_handler" {
  filename         = "${path.module}/../LambdaOrchestrateDeleteHandler/target/LambdaOrchestrateDeleteHandler-1.0-SNAPSHOT.jar"
  function_name    = "LambdaOrchestrateDeleteHandler"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaOrchestrateDeleteHandler::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaOrchestrateDeleteHandler/target/LambdaOrchestrateDeleteHandler-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      # Worker Lambda function names
      DELETE_FROM_S3_FUNC = aws_lambda_function.delete_objects.function_name
      DELETE_FROM_DB_FUNC = aws_lambda_function.delete_objects.function_name
      DELETE_RESIZED_FUNC = aws_lambda_function.delete_resized_object.function_name
      # RDS config for ownership verification
      RDS_HOSTNAME = module.database.rds_instance_address
      RDS_PORT     = tostring(module.database.rds_instance_port)
      DB_USER      = "admin"
      DB_PASSWORD  = var.db_password
      DB_NAME      = "Cloud26"
    }
  }

  depends_on = [
    aws_s3_bucket.source_bucket,
    aws_s3_bucket.resized_bucket,
    module.database
  ]
}

# CloudWatch Log Group for LambdaOrchestrateDeleteHandler
resource "aws_cloudwatch_log_group" "orchestrate_delete_handler_logs" {
  name              = "/aws/lambda/${aws_lambda_function.orchestrate_delete_handler.function_name}"
  retention_in_days = 14
}

# NOTE: Lambda Permission moved to 10-api_gateway.tf 
# with the new dedicated /delete-orchestrator route
# resource "aws_lambda_permission" "api_gateway_orchestrate_delete_handler" {
#   statement_id  = "AllowAPIGatewayInvokeOrchestrateDeleteHandler"
#   action        = "lambda:InvokeFunction"
#   function_name = aws_lambda_function.orchestrate_delete_handler.function_name
#   principal     = "apigateway.amazonaws.com"
#   source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
# }



