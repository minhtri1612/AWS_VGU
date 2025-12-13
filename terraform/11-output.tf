
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

output "lambda_get_resized_image_arn" {
  description = "Lambda GetResizedImage ARN"
  value       = aws_lambda_function.get_resized_image.arn
}

output "lambda_get_photos_db_arn" {
  description = "Lambda GetPhotosDB ARN"
  value       = aws_lambda_function.get_photos_db.arn
}

output "lambda_add_photo_db_arn" {
  description = "Lambda AddPhotoDB ARN"
  value       = aws_lambda_function.add_photo_db.arn
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

output "orchestrator_function_url" {
  description = "Lambda Function URL for Orchestrator"
  value       = aws_lambda_function_url.orchestrate_upload.function_url
}

output "lambda_orchestrate_upload_arn" {
  description = "Lambda OrchestrateUploadHandler ARN"
  value       = aws_lambda_function.orchestrate_upload.arn
}

output "lambda_resize_wrapper_arn" {
  description = "Lambda ResizeWrapper ARN"
  value       = aws_lambda_function.resize_wrapper.arn
}

output "get_objects_function_url" {
  description = "Lambda Function URL for GetObjects (Download)"
  value       = aws_lambda_function_url.get_objects.function_url
}

output "delete_objects_function_url" {
  description = "Lambda Function URL for DeleteObjects"
  value       = aws_lambda_function_url.delete_objects.function_url
}

output "get_list_of_objects_function_url" {
  description = "Lambda Function URL for GetListOfObjects"
  value       = aws_lambda_function_url.get_list_of_objects.function_url
}

output "get_resized_image_function_url" {
  description = "Lambda Function URL for GetResizedImage (Thumbnail)"
  value       = aws_lambda_function_url.get_resized_image.function_url
}