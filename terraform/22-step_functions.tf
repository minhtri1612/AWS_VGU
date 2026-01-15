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

# Policy to allow Step Functions to send events to EventBridge
resource "aws_iam_role_policy" "step_functions_eventbridge_policy" {
  name = "${var.project_name}-step-functions-eventbridge-policy"
  role = aws_iam_role.step_functions_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "events:PutEvents"
        ]
        Resource = [
          "arn:aws:events:${var.aws_region}:${data.aws_caller_identity.current.account_id}:event-bus/default",
          aws_cloudwatch_event_bus.upload_events.arn
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
    Comment = "Upload-A-File Workflow: Execute 3 activities concurrently"
    StartAt = "ParallelExecution"
    States = {
      ParallelExecution = {
        Type = "Parallel"
        Branches = [
          {
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
                Retry = [
                  {
                    ErrorEquals = [
                      "Lambda.ServiceException",
                      "Lambda.AWSLambdaException",
                      "Lambda.SdkClientException"
                    ]
                    IntervalSeconds = 2
                    MaxAttempts = 3
                    BackoffRate = 2.0
                  }
                ]
                Catch = [
                  {
                    ErrorEquals = ["States.ALL"]
                    ResultPath = "$.error"
                    Next = "Activity1_Failed"
                  }
                ]
                ResultPath = "$.activity1_result"
                OutputPath = "$.activity1_result.Payload.body"
                End = true
              }
              Activity1_Failed = {
                Type = "Pass"
                Result = {
                  "error": "Activity 1 (Database) failed"
                  "cause.$": "$.error.Cause"
                }
                End = true
              }
            }
          },
          {
            StartAt = "Activity2_OriginalS3"
            States = {
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
                Retry = [
                  {
                    ErrorEquals = [
                      "Lambda.ServiceException",
                      "Lambda.AWSLambdaException",
                      "Lambda.SdkClientException"
                    ]
                    IntervalSeconds = 2
                    MaxAttempts = 3
                    BackoffRate = 2.0
                  }
                ]
                Catch = [
                  {
                    ErrorEquals = ["States.ALL"]
                    ResultPath = "$.error"
                    Next = "Activity2_Failed"
                  }
                ]
                ResultPath = "$.activity2_result"
                OutputPath = "$.activity2_result.Payload.body"
                End = true
              }
              Activity2_Failed = {
                Type = "Pass"
                Result = {
                  "error": "Activity 2 (Original S3) failed"
                  "cause.$": "$.error.Cause"
                }
                End = true
              }
            }
          },
          {
            StartAt = "Activity3_ResizeS3"
            States = {
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
                Retry = [
                  {
                    ErrorEquals = [
                      "Lambda.ServiceException",
                      "Lambda.AWSLambdaException",
                      "Lambda.SdkClientException"
                    ]
                    IntervalSeconds = 2
                    MaxAttempts = 3
                    BackoffRate = 2.0
                  }
                ]
                Catch = [
                  {
                    ErrorEquals = ["States.ALL"]
                    ResultPath = "$.error"
                    Next = "Activity3_Failed"
                  }
                ]
                ResultPath = "$.activity3_result"
                OutputPath = "$.activity3_result.Payload.body"
                End = true
              }
              Activity3_Failed = {
                Type = "Pass"
                Result = {
                  "error": "Activity 3 (Resize S3) failed"
                  "cause.$": "$.error.Cause"
                }
                End = true
              }
            }
          }
        ]
        ResultPath = "$.parallel_results"
        Next = "FilterResults"
      },
      FilterResults = {
        Type = "Pass"
        Parameters = {
          "activity1.$": "$[0]"
          "activity2.$": "$[1]"
          "activity3.$": "$[2]"
        }
        InputPath = "$.parallel_results"
        ResultPath = "$.filtered_results"
        Next = "SendCompletionEvent"
      },
      SendCompletionEvent = {
        Type = "Task"
        Resource = "arn:aws:states:::events:putEvents"
        Parameters = {
          Entries = [
            {
              Source = "step.functions.upload"
              DetailType = "Upload Workflow Completed"
              Detail = {
                "status": "Completed"
                "timestamp.$": "$$.State.EnteredTime"
              }
            }
          ]
        }
        ResultPath = "$.eventbridge_result"
        OutputPath = "$"
        Next = "ExtractResults"
      },
      ExtractResults = {
        Type = "Pass"
        Parameters = {
          "Activity_1_Database.$": "$.filtered_results.activity1"
          "Activity_2_Original_S3.$": "$.filtered_results.activity2"
          "Activity_3_Resize_S3.$": "$.filtered_results.activity3"
        }
        InputPath = "$"
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

# ============================================
# EVENTBRIDGE INTEGRATION WITH STEP FUNCTIONS
# ============================================

# Custom EventBridge Event Bus (optional - for custom events)
resource "aws_cloudwatch_event_bus" "upload_events" {
  name = "${var.project_name}-upload-events"
}

# Pattern 1: EventBridge Rule - S3 Upload triggers Step Functions
# This triggers Step Functions when a file is uploaded to S3
resource "aws_cloudwatch_event_rule" "s3_upload_trigger" {
  name        = "${var.project_name}-s3-upload-trigger-step-functions"
  description = "Trigger Step Functions workflow when file is uploaded to S3"
  
  event_pattern = jsonencode({
    source      = ["aws.s3"]
    detail-type = ["Object Created"]
    detail = {
      bucket = {
        name = [aws_s3_bucket.source_bucket.id]
      }
      object = {
        key = [{
          prefix = ""
        }]
      }
    }
  })

  state = "ENABLED"
}

resource "aws_cloudwatch_event_target" "s3_upload_step_functions" {
  rule      = aws_cloudwatch_event_rule.s3_upload_trigger.name
  target_id = "TriggerStepFunctions"
  arn       = aws_sfn_state_machine.upload_workflow.arn
  role_arn  = aws_iam_role.eventbridge_step_functions_role.arn
  
  # Transform S3 event to Step Functions input format
  input_transformer {
    input_paths = {
      s3_bucket = "$.detail.bucket.name"
      s3_key    = "$.detail.object.key"
    }
    input_template = <<-EOT
    {
      "body": "{\"key\": \"<s3_key>\", \"description\": \"Auto-uploaded from S3\", \"content\": \"\"}"
    }
    EOT
  }
}

# IAM Role for EventBridge to invoke Step Functions
resource "aws_iam_role" "eventbridge_step_functions_role" {
  name = "${var.project_name}-eventbridge-step-functions-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "events.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
}

# Policy for EventBridge to start Step Functions executions
resource "aws_iam_role_policy" "eventbridge_start_step_functions" {
  name = "${var.project_name}-eventbridge-start-step-functions"
  role = aws_iam_role.eventbridge_step_functions_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "states:StartExecution"
        ]
        Resource = aws_sfn_state_machine.upload_workflow.arn
      }
    ]
  })
}

# Pattern 2: EventBridge Rule - Schedule Step Functions (Warm-up)
# DISABLED: Warmup creates fake files in S3/DB - not needed
# This runs Step Functions every 5 minutes to keep it warm
resource "aws_cloudwatch_event_rule" "warmup_step_functions" {
  name                = "${var.project_name}-warmup-step-functions"
  description         = "Warm up Step Functions state machine to reduce cold starts"
  schedule_expression = "rate(5 minutes)"
  
  state = "DISABLED"  # Disabled to prevent creating warmup-test.jpg files
}

resource "aws_cloudwatch_event_target" "warmup_step_functions" {
  rule      = aws_cloudwatch_event_rule.warmup_step_functions.name
  target_id = "WarmupStepFunctions"
  arn       = aws_sfn_state_machine.upload_workflow.arn
  role_arn  = aws_iam_role.eventbridge_step_functions_role.arn
  
  input = jsonencode({
    body = jsonencode({
      source = "eventbridge-warmup"
      action = "warmup"
      key    = "warmup-test.jpg"
      description = "Warm-up test"
      content = ""
    })
  })
}

# Pattern 3: EventBridge Rule - Listen to Step Functions completion events
# This triggers when Step Functions sends completion events
resource "aws_cloudwatch_event_rule" "step_functions_completion" {
  name        = "${var.project_name}-step-functions-completion"
  description = "Listen to Step Functions workflow completion events"
  
  event_pattern = jsonencode({
    source      = ["step.functions.upload"]
    detail-type = ["Upload Workflow Completed"]
  })

  state = "ENABLED"
}

# You can add targets here - e.g., send notification, trigger another workflow, etc.
resource "aws_cloudwatch_event_target" "step_functions_completion_notification" {
  rule      = aws_cloudwatch_event_rule.step_functions_completion.name
  target_id = "LogCompletion"
  
  # Example: Send to CloudWatch Logs
  arn = aws_cloudwatch_log_group.step_functions_completion_logs.arn
}

resource "aws_cloudwatch_log_group" "step_functions_completion_logs" {
  name              = "/aws/events/${var.project_name}-step-functions-completion"
  retention_in_days = 7
}

# Data source for account ID
data "aws_caller_identity" "current" {}

