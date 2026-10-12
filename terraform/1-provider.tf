terraform {
  required_version = ">= 1.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "5.50.0"
    }
  }

  backend "s3" {
    bucket         = "minhtri-devops-cloud-tfstate-864992048128"
    key            = "aws-vgu/terraform.tfstate"
    region         = "ap-southeast-2"
    dynamodb_table = "minhtri-devops-cloud-tfstate-lock"
    encrypt        = true
  }
}

# ========================================
# PROVIDER
# ========================================
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "AWS_clavel"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}