# AWS Parameters and Secrets Lambda Extension Layer
# ARN format: arn:aws:lambda:<region>:590474943231:layer:AWS-Parameters-and-Secrets-Lambda-Extension:<version>
# Latest version as of 2024: version 11
# For ap-southeast-2, use version 11
locals {
  ssm_extension_layer_arn = "arn:aws:lambda:ap-southeast-2:590474943231:layer:AWS-Parameters-and-Secrets-Lambda-Extension:11"
}

# SSM Parameter Store for SECRET_KEY
resource "aws_ssm_parameter" "secret_key" {
  name        = "keytokenhash"
  description = "Secret key for HMAC-SHA256 token generation"
  type        = "SecureString"
  value       = var.secret_key
  overwrite   = true  # Allow overwriting existing parameter

  tags = {
    Name        = "${var.project_name}-secret-key"
    Environment = var.environment
  }
}

