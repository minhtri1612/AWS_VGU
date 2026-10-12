# Source bucket for uploads and downloads
resource "aws_s3_bucket" "source_bucket" {
  bucket = var.source_bucket_name
}

resource "aws_s3_bucket_versioning" "source_bucket_versioning" {
  bucket = aws_s3_bucket.source_bucket.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_cors_configuration" "source_bucket_cors" {
  bucket = aws_s3_bucket.source_bucket.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT", "POST", "DELETE", "HEAD"]
    allowed_origins = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# Enable static website hosting
resource "aws_s3_bucket_website_configuration" "source_bucket_website" {
  bucket = aws_s3_bucket.source_bucket.id

  index_document {
    suffix = "index.html"
  }
}

# Make bucket public for website access
resource "aws_s3_bucket_public_access_block" "source_bucket_public" {
  bucket = aws_s3_bucket.source_bucket.id

  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

# Bucket policy to allow public read access
resource "aws_s3_bucket_policy" "source_bucket_policy" {
  bucket = aws_s3_bucket.source_bucket.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "PublicReadGetObject"
        Effect    = "Allow"
        Principal = "*"
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.source_bucket.arn}/*"
      }
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.source_bucket_public]
}

# Destination bucket for resized images
resource "aws_s3_bucket" "resized_bucket" {
  bucket = var.resized_bucket_name
}

resource "aws_s3_bucket_versioning" "resized_bucket_versioning" {
  bucket = aws_s3_bucket.resized_bucket.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Upload index.html with the API URL from this apply, not the placeholder in git.
resource "aws_s3_object" "index_html" {
  bucket       = aws_s3_bucket.source_bucket.id
  key          = "index.html"
  content      = replace(file("${path.module}/../index.html"), "__API_GATEWAY_BASE__", "https://${aws_api_gateway_rest_api.main.id}.execute-api.${var.aws_region}.amazonaws.com/${var.environment}")
  content_type = "text/html"
  etag         = md5(replace(file("${path.module}/../index.html"), "__API_GATEWAY_BASE__", "https://${aws_api_gateway_rest_api.main.id}.execute-api.${var.aws_region}.amazonaws.com/${var.environment}"))
}
