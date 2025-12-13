resource "aws_lambda_function" "entry_point" {
  filename         = "${path.module}/../LambdaEntryPoint/target/LambdaEntryPoint-1.0-SNAPSHOT.jar"
  function_name    = "LambdaEntryPoint"
  role             = aws_iam_role.lambda_role.arn
  handler          = "vgu.cloud26.LambdaEntryPoint::handleRequest"
  source_code_hash = filebase64sha256("${path.module}/../LambdaEntryPoint/target/LambdaEntryPoint-1.0-SNAPSHOT.jar")
  runtime          = var.lambda_runtime
  timeout          = var.lambda_timeout
  memory_size      = var.lambda_memory

  environment {
    variables = {
      DELETE_FUNC_NAME      = aws_lambda_function.delete_objects.function_name
      GET_FUNC_NAME         = aws_lambda_function.get_objects.function_name
      UPLOAD_FUNC_NAME      = aws_lambda_function.upload_objects.function_name
      LIST_FUNC_NAME        = aws_lambda_function.get_list_of_objects.function_name
      GET_RESIZED_FUNC_NAME = aws_lambda_function.get_resized_image.function_name
      GET_PHOTOS_DB_FUNC_NAME = aws_lambda_function.get_photos_db.function_name
      ADD_PHOTO_DB_FUNC_NAME = aws_lambda_function.add_photo_db.function_name
    }
  }

  depends_on = [
    aws_lambda_function.get_resized_image
  ]
}