# CloudWatch Log Groups
resource "aws_cloudwatch_log_group" "entry_point_logs" {
  name              = "/aws/lambda/${aws_lambda_function.entry_point.function_name}"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "get_objects_logs" {
  name              = "/aws/lambda/${aws_lambda_function.get_objects.function_name}"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "upload_objects_logs" {
  name              = "/aws/lambda/${aws_lambda_function.upload_objects.function_name}"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "delete_objects_logs" {
  name              = "/aws/lambda/${aws_lambda_function.delete_objects.function_name}"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "resize_logs" {
  name              = "/aws/lambda/${aws_lambda_function.resize.function_name}"
  retention_in_days = 7
}
