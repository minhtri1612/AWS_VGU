# Lambda Token Checker Function
resource "aws_lambda_function" "token_checker" {
  filename         = "${path.module}/../LambdaTokenChecker/target/LambdaTokenChecker-1.0-SNAPSHOT.jar"
  function_name    = "LambdaTokenChecker"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaTokenChecker::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaTokenChecker/target/LambdaTokenChecker-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  # Note: Extension Layer requires special permissions or use SDK directly
  # For now, we'll use SDK calls (slower but works without layer permissions)
  # layers = [local.ssm_extension_layer_arn]

  environment {
    variables = {
      # SECRET_KEY removed - now retrieved from Parameter Store via HTTP
    }
  }
}

# CloudWatch Log Group for LambdaTokenChecker
resource "aws_cloudwatch_log_group" "token_checker_logs" {
  name              = "/aws/lambda/${aws_lambda_function.token_checker.function_name}"
  retention_in_days = 14
}

# Lambda Permission for API Gateway to invoke Token Checker
resource "aws_lambda_permission" "api_gateway_token_checker" {
  statement_id  = "AllowAPIGatewayInvokeTokenChecker"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.token_checker.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

# Lambda Permission for LambdaGetObject to invoke Token Checker
resource "aws_lambda_permission" "lambda_get_object_token_checker" {
  statement_id  = "AllowLambdaGetObjectInvokeTokenChecker"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.token_checker.function_name
  principal     = "lambda.amazonaws.com"
  source_arn    = aws_lambda_function.get_objects.arn
}




