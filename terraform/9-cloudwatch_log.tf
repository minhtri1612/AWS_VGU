# CloudWatch log groups for every Lambda. Retention is 7 days.
locals {
  lambda_log_functions = {
    entry_point                = aws_lambda_function.entry_point.function_name
    get_objects                = aws_lambda_function.get_objects.function_name
    upload_objects             = aws_lambda_function.upload_objects.function_name
    delete_objects             = aws_lambda_function.delete_objects.function_name
    delete_resized_object      = aws_lambda_function.delete_resized_object.function_name
    get_resized_image          = aws_lambda_function.get_resized_image.function_name
    get_photos_db              = aws_lambda_function.get_photos_db.function_name
    add_photo_db               = aws_lambda_function.add_photo_db.function_name
    resize_wrapper             = aws_lambda_function.resize_wrapper.function_name
    orchestrate_upload         = aws_lambda_function.orchestrate_upload.function_name
    orchestrate_delete_handler = aws_lambda_function.orchestrate_delete_handler.function_name
    token_checker              = aws_lambda_function.token_checker.function_name
    generate_token             = aws_lambda_function.generate_token.function_name
  }
}

resource "aws_cloudwatch_log_group" "lambda" {
  for_each = local.lambda_log_functions

  name              = "/aws/lambda/${each.value}"
  retention_in_days = 7
}
