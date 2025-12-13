resource "aws_iam_role" "lambda_role" {
  name = "${var.project_name}-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

# Basic Lambda execution policy
resource "aws_iam_role_policy_attachment" "lambda_basic_execution" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# S3 access policy for Lambda
resource "aws_iam_role_policy" "lambda_s3_policy" {
  name = "${var.project_name}-lambda-s3-policy"
  role = aws_iam_role.lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket",
          "s3:HeadObject"
        ]
        Resource = [
          "${aws_s3_bucket.source_bucket.arn}",
          "${aws_s3_bucket.source_bucket.arn}/*",
          "${aws_s3_bucket.resized_bucket.arn}",
          "${aws_s3_bucket.resized_bucket.arn}/*"
        ]
      }
    ]
  })
}

# Lambda invoke policy for EntryPoint to invoke other Lambdas
resource "aws_iam_role_policy" "lambda_invoke_policy" {
  name = "${var.project_name}-lambda-invoke-policy"
  role = aws_iam_role.lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "lambda:InvokeFunction"
        ]
        Resource = [
          aws_lambda_function.get_objects.arn,
          aws_lambda_function.upload_objects.arn,
          aws_lambda_function.delete_objects.arn,
          aws_lambda_function.get_list_of_objects.arn,
          aws_lambda_function.get_resized_image.arn,
          aws_lambda_function.get_photos_db.arn,
          aws_lambda_function.add_photo_db.arn,
          aws_lambda_function.orchestrate_upload.arn,
          aws_lambda_function.resize_wrapper.arn
        ]
      }
    ]
  })
}


resource "aws_iam_role" "amplify_service_role" {
  name = "amplify-service-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "amplify.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "amplify_policy" {
  role       = aws_iam_role.amplify_service_role.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess-Amplify"
}
