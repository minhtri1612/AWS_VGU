# Lambda Token Checker Function
resource "aws_lambda_function" "token_checker" {
  filename         = "${path.module}/../LambdaTokenChecker/lambda.zip"
  function_name    = "LambdaTokenChecker"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaTokenChecker/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory
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




