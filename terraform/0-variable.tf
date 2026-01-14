variable "aws_region" {
  description = "AWS region for resources"
  type        = string
  default     = "ap-southeast-2"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "dev"
}

variable "project_name" {
  description = "Project name prefix"
  type        = string
  default     = "minhtri-devops-cloud"
}

variable "source_bucket_name" {
  description = "S3 bucket name for original objects"
  type        = string
  default     = "minhtri-devops-cloud-getobjects-864992048128"
}

variable "resized_bucket_name" {
  description = "S3 bucket name for resized images"
  type        = string
  default     = "minhtri-devops-cloud-resized-864992048128"
}

variable "lambda_timeout" {
  description = "Lambda function timeout in seconds"
  type        = number
  default     = 30
}

variable "lambda_memory" {
  description = "Lambda function memory in MB"
  type        = number
  default     = 512
}

variable "lambda_runtime" {
  description = "Lambda runtime"
  type        = string
  # IMPORTANT: Make sure this matches your actual Java version (java11 or java17)
  default = "java17"
}

variable "db_password" {
  description = "Database master password"
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "Secret key for HMAC token generation"
  type        = string
  sensitive   = true
  default     = "CHANGE_ME_SECRET_KEY_FOR_HMAC"
}

