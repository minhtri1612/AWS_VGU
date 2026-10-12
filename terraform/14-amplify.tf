# Amplify app with GitHub auto-deployment
resource "aws_amplify_app" "my_app" {
  name                 = "AWS_clavelier_${var.environment}"
  repository           = var.github_repo
  access_token         = var.github_token
  iam_service_role_arn = aws_iam_role.amplify_service_role.arn

  environment_variables = {
    API_GATEWAY_BASE = "https://${aws_api_gateway_rest_api.main.id}.execute-api.${var.aws_region}.amazonaws.com/${var.environment}"
  }

  # Auto-build on every commit
  auto_branch_creation_config {
    enable_auto_build = true
  }
}

# Optional: Connect main branch
resource "aws_amplify_branch" "main" {
  app_id            = aws_amplify_app.my_app.id
  branch_name       = "main"
  framework         = "HTML"
  enable_auto_build = true
}

output "amplify_app_id" {
  value = aws_amplify_app.my_app.id
}

output "amplify_app_url" {
  value       = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.my_app.default_domain}"
  description = "Amplify app URL"
}
