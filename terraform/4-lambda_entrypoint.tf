resource "aws_lambda_function" "entry_point" {
  filename         = "${path.module}/../LambdaEntryPoint/lambda.zip"
  function_name    = "LambdaEntryPoint"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaEntryPoint/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      DELETE_FUNC_NAME        = aws_lambda_function.orchestrate_delete_handler.function_name
      GET_FUNC_NAME           = aws_lambda_function.get_objects.function_name
      UPLOAD_FUNC_NAME        = aws_lambda_function.orchestrate_upload.function_name
      GET_RESIZED_FUNC_NAME   = aws_lambda_function.get_resized_image.function_name
      GET_PHOTOS_DB_FUNC_NAME = aws_lambda_function.get_photos_db.function_name
      ADD_PHOTO_DB_FUNC_NAME  = aws_lambda_function.add_photo_db.function_name
    }
  }

  depends_on = [
    aws_lambda_function.get_resized_image,
    aws_lambda_function.orchestrate_delete_handler
  ]
}
