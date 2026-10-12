# ============================================
# STEP FUNCTIONS STATE MACHINE
# ============================================

# Step Functions IAM Role
resource "aws_iam_role" "step_functions_role" {
  name = "${var.project_name}-step-functions-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "states.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
}

# Policy to allow Step Functions to invoke Lambda functions
resource "aws_iam_role_policy" "step_functions_lambda_policy" {
  name = "${var.project_name}-step-functions-lambda-policy"
  role = aws_iam_role.step_functions_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "lambda:InvokeFunction"
        ]
        Resource = [
          aws_lambda_function.add_photo_db.arn,
          aws_lambda_function.upload_objects.arn,
          aws_lambda_function.resize_wrapper.arn
        ]
      }
    ]
  })
}

# Step Functions State Machine Definition
resource "aws_sfn_state_machine" "upload_workflow" {
  name     = "${var.project_name}-upload-workflow"
  role_arn = aws_iam_role.step_functions_role.arn

  definition = jsonencode({
    Comment = "Upload workflow: database, then original S3, then resize"
    StartAt = "Activity1_Database"
    States = {
      Activity1_Database = {
        Type = "Task"
        Resource = "arn:aws:states:::lambda:invoke"
        Parameters = {
          FunctionName = aws_lambda_function.add_photo_db.function_name
          Payload = {
            "httpMethod": "POST"
            "body.$": "$.body"
            "headers": {
              "Content-Type": "application/json"
            }
          }
        }
        ResultPath = "$.activity1_result"
        Next = "CheckDatabase"
      }
      CheckDatabase = {
        Type = "Choice"
        Choices = [
          {
            Variable = "$.activity1_result.Payload.statusCode"
            NumericEquals = 200
            Next = "Activity2_OriginalS3"
          }
        ]
        Default = "DatabaseFailed"
      }
      DatabaseFailed = {
        Type = "Fail"
        Error = "DatabaseInsertFailed"
        Cause = "Activity 1 (Database) did not return 200"
      }
      Activity2_OriginalS3 = {
        Type = "Task"
        Resource = "arn:aws:states:::lambda:invoke"
        Parameters = {
          FunctionName = aws_lambda_function.upload_objects.function_name
          Payload = {
            "httpMethod": "POST"
            "body.$": "$.body"
            "headers": {
              "Content-Type": "application/json"
            }
          }
        }
        ResultPath = "$.activity2_result"
        Next = "CheckUpload"
      }
      CheckUpload = {
        Type = "Choice"
        Choices = [
          {
            Variable = "$.activity2_result.Payload.statusCode"
            NumericEquals = 200
            Next = "Activity3_ResizeS3"
          }
        ]
        Default = "UploadFailed"
      }
      UploadFailed = {
        Type = "Fail"
        Error = "OriginalUploadFailed"
        Cause = "Activity 2 (Original S3) did not return 200"
      }
      Activity3_ResizeS3 = {
        Type = "Task"
        Resource = "arn:aws:states:::lambda:invoke"
        Parameters = {
          FunctionName = aws_lambda_function.resize_wrapper.function_name
          Payload = {
            "httpMethod": "POST"
            "body.$": "$.body"
            "headers": {
              "Content-Type": "application/json"
            }
          }
        }
        ResultPath = "$.activity3_result"
        Catch = [
          {
            ErrorEquals = ["States.ALL"]
            ResultPath = "$.activity3_error"
            Next = "ResizeSkipped"
          }
        ]
        Next = "ExtractResults"
      }
      ResizeSkipped = {
        Type = "Pass"
        Result = {
          Payload = {
            body = "Resize skipped"
          }
        }
        ResultPath = "$.activity3_result"
        Next = "ExtractResults"
      }
      ExtractResults = {
        Type = "Pass"
        Parameters = {
          "Activity_1_Database.$": "$.activity1_result.Payload.body"
          "Activity_2_Original_S3.$": "$.activity2_result.Payload.body"
          "Activity_3_Resize_S3.$": "$.activity3_result.Payload.body"
        }
        End = true
      }
    }
  })

  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.step_functions_logs.arn}:*"
    include_execution_data = true
    level                  = "ALL"
  }

  depends_on = [
    aws_lambda_function.add_photo_db,
    aws_lambda_function.upload_objects,
    aws_lambda_function.resize_wrapper
  ]
}

# CloudWatch Log Group for Step Functions
resource "aws_cloudwatch_log_group" "step_functions_logs" {
  name              = "/aws/vendedlogs/states/${var.project_name}-upload-workflow"
  retention_in_days = 7
}

# IAM Policy for Step Functions to write logs
resource "aws_iam_role_policy" "step_functions_logs_policy" {
  name = "${var.project_name}-step-functions-logs-policy"
  role = aws_iam_role.step_functions_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogDelivery",
          "logs:GetLogDelivery",
          "logs:UpdateLogDelivery",
          "logs:DeleteLogDelivery",
          "logs:ListLogDeliveries",
          "logs:PutResourcePolicy",
          "logs:DescribeResourcePolicies",
          "logs:DescribeLogGroups"
        ]
        Resource = "*"
      }
    ]
  })
}
