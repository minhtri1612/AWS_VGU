# Lambda Generate Token Function
resource "aws_lambda_function" "generate_token" {
  filename         = "${path.module}/../LambdaGenerateToken/target/LambdaGenerateToken-1.0-SNAPSHOT.jar"
  function_name    = "LambdaGenerateToken"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaGenerateToken::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaGenerateToken/target/LambdaGenerateToken-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  # Note: Extension Layer requires special permissions or use SDK directly
  # For now, we'll attach manually via AWS Console or use SDK
  # layers = [local.ssm_extension_layer_arn]

  environment {
    variables = {
      # SECRET_KEY removed - now retrieved from Parameter Store via HTTP
      # FROM_EMAIL removed - not used (token returned directly, no email sent)
    }
  }
}

# CloudWatch Log Group for LambdaGenerateToken
resource "aws_cloudwatch_log_group" "generate_token_logs" {
  name              = "/aws/lambda/${aws_lambda_function.generate_token.function_name}"
  retention_in_days = 14
}

# Lambda Permission for API Gateway to invoke Generate Token
resource "aws_lambda_permission" "api_gateway_generate_token" {
  statement_id  = "AllowAPIGatewayInvokeGenerateToken"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.generate_token.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

