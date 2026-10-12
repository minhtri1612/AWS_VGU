# Lambda Generate Token Function
resource "aws_lambda_function" "generate_token" {
  filename         = "${path.module}/../LambdaGenerateToken/lambda.zip"
  function_name    = "LambdaGenerateToken"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaGenerateToken/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      FROM_EMAIL              = var.from_email
      SECRET_KEY              = var.secret_key
      TOKEN_CHECKER_FUNC_NAME = aws_lambda_function.token_checker.function_name
    }
  }
}

# Lambda Permission for API Gateway to invoke Generate Token
resource "aws_lambda_permission" "api_gateway_generate_token" {
  statement_id  = "AllowAPIGatewayInvokeGenerateToken"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.generate_token.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

