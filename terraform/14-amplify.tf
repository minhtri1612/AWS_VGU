# Amplify app with GitHub auto-deployment
resource "aws_amplify_app" "my_app" {
  name                 = "AWS_clavelier_${var.environment}"
  repository           = var.github_repo
  access_token         = var.github_token
  iam_service_role_arn = aws_iam_role.amplify_service_role.arn

  # Auto-build on every commit
  auto_branch_creation_config {
    enable_auto_build = true
  }
}

# Optional: Connect main branch
resource "aws_amplify_branch" "main" {
  app_id            = aws_amplify_app.my_app.id
  branch_name       = "main"
  framework         = "HTML" # since you said only html
  enable_auto_build = true
}

# Output the Amplify app URL
output "amplify_app_url" {
  value       = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.my_app.default_domain}"
  description = "Amplify app URL"
}
