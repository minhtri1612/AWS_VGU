# Lambda Orchestrate Upload Handler
resource "aws_lambda_function" "orchestrate_upload" {
  filename         = "${path.module}/../LambdaOrchestrateUploadHandler/lambda.zip"
  function_name    = "LambdaOrchestrateUploadHandler"
  role             = aws_iam_role.lambda_role.arn
  handler          = "index.handler"
  source_code_hash = filebase64sha256("${path.module}/../LambdaOrchestrateUploadHandler/lambda.zip")
  runtime          = var.lambda_runtime
  timeout          = 300
  memory_size      = 512

  environment {
    variables = {
      ADD_PHOTO_DB_FUNC_NAME   = aws_lambda_function.add_photo_db.function_name
      UPLOAD_OBJECTS_FUNC_NAME = aws_lambda_function.upload_objects.function_name
      RESIZE_WRAPPER_FUNC_NAME = aws_lambda_function.resize_wrapper.function_name
      STATE_MACHINE_ARN        = aws_sfn_state_machine.upload_workflow.arn
      TOKEN_CHECKER_FUNC_NAME  = aws_lambda_function.token_checker.function_name
    }
  }

  depends_on = [
    aws_lambda_function.add_photo_db,
    aws_lambda_function.upload_objects,
    aws_lambda_function.resize_wrapper,
    aws_sfn_state_machine.upload_workflow
  ]
}

# Lambda Function URL for Orchestrator
resource "aws_lambda_function_url" "orchestrate_upload" {
  function_name      = aws_lambda_function.orchestrate_upload.function_name
  authorization_type = "NONE"

  cors {
    allow_credentials = false
    allow_origins     = ["*"]
    allow_methods     = ["*"]
    allow_headers     = ["*"]
    expose_headers    = []
    max_age           = 0
  }
}

# Note: Lambda Function URL with authorization_type = "NONE" automatically creates
# the resource-based policy. No explicit permission needed via Terraform.

