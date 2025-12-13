
output "api_gateway_url" {
  description = "API Gateway endpoint URL"
  # Manually construct the URL: https://{api_id}.execute-api.{region}.amazonaws.com/{stage_name}
  value = "https://${aws_api_gateway_rest_api.main.id}.execute-api.${var.aws_region}.amazonaws.com/${var.environment}"
}

output "source_bucket_name" {
  description = "Source S3 bucket name"
  value       = aws_s3_bucket.source_bucket.id
}

output "resized_bucket_name" {
  description = "Resized S3 bucket name"
  value       = aws_s3_bucket.resized_bucket.id
}

output "lambda_entry_point_arn" {
  description = "Lambda EntryPoint ARN"
  value       = aws_lambda_function.entry_point.arn
}

output "lambda_get_objects_arn" {
  description = "Lambda GetObjects ARN"
  value       = aws_lambda_function.get_objects.arn
}

output "lambda_upload_objects_arn" {
  description = "Lambda UploadObjects ARN"
  value       = aws_lambda_function.upload_objects.arn
}

output "lambda_delete_objects_arn" {
  description = "Lambda DeleteObjects ARN"
  value       = aws_lambda_function.delete_objects.arn
}

output "lambda_resize_arn" {
  description = "Lambda Resize ARN"
  value       = aws_lambda_function.resize.arn
}

output "lambda_get_list_of_objects_arn" {
  description = "Lambda GetListOfObjects ARN"
  value       = aws_lambda_function.get_list_of_objects.arn
}

output "lambda_delete_resized_object_arn" {
  description = "Lambda DeleteResizedObject ARN"
  value       = aws_lambda_function.delete_resized_object.arn
}

output "rds_endpoint" {
  value = module.database.rds_instance_endpoint
}

output "static_website_url" {
  description = "S3 Static Website URL"
  value       = aws_s3_bucket_website_configuration.source_bucket_website.website_endpoint
}

output "static_website_domain" {
  description = "S3 Static Website Domain"
  value       = aws_s3_bucket_website_configuration.source_bucket_website.website_domain
}