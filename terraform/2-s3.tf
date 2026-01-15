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

# S3 bucket notification to trigger Lambda resize function AND delete cleanup
resource "aws_s3_bucket_notification" "source_bucket_notification" {
  bucket = aws_s3_bucket.source_bucket.id

  # Trigger resize on object creation
  lambda_function {
    lambda_function_arn = aws_lambda_function.resize.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = ""
    filter_suffix       = ""
  }

  # Trigger cleanup on object deletion
  lambda_function {
    lambda_function_arn = aws_lambda_function.delete_resized_object.arn
    events              = ["s3:ObjectRemoved:*"]
    filter_prefix       = ""
    filter_suffix       = ""
  }

  depends_on = [
    aws_lambda_permission.allow_s3_invoke_resize,
    aws_lambda_permission.allow_s3_invoke_delete_resized
  ]
}

# Upload index.html to source bucket
resource "aws_s3_object" "index_html" {
  bucket       = aws_s3_bucket.source_bucket.id
  key          = "index.html"
  source       = "${path.module}/../index.html"
  content_type = "text/html"
  etag         = filemd5("${path.module}/../index.html")
}
