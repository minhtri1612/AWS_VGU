# Troubleshooting Guide

## Network Error: "NetworkError when attempting to fetch resource"

This error typically occurs when the frontend cannot reach the Lambda Function URLs. Here's how to fix it:

### Step 1: Verify Lambda Function URLs

The URLs in `index.html` are hardcoded and might be outdated. Get the correct URLs:

```bash
cd terraform
terraform output orchestrator_function_url
terraform output get_list_of_objects_function_url
terraform output get_objects_function_url
terraform output delete_objects_function_url
terraform output get_resized_image_function_url
```

Or get all at once:
```bash
terraform output -json | jq -r '.orchestrator_function_url.value'
terraform output -json | jq -r '.get_list_of_objects_function_url.value'
terraform output -json | jq -r '.get_objects_function_url.value'
terraform output -json | jq -r '.delete_objects_function_url.value'
terraform output -json | jq -r '.get_resized_image_function_url.value'
```

### Step 2: Update index.html

Replace the hardcoded URLs in `index.html` with the correct values from Step 1.

### Step 3: Verify Lambda Functions are Deployed

Check AWS Console:
1. Go to Lambda > Functions
2. Verify these functions exist:
   - `LambdaOrchestrateUploadHandler`
   - `LambdaGetListOfObjects`
   - `LambdaGetObjects`
   - `LambdaDeleteObjects`
   - `LambdaGetResizedImage`

### Step 4: Verify Function URLs are Enabled

For each Lambda function:
1. Open the function in AWS Console
2. Go to **Configuration** > **Function URL**
3. Verify:
   - Function URL is **Enabled**
   - Authorization type is **NONE**
   - CORS is configured with:
     - Allow origins: `*`
     - Allow methods: `*`
     - Allow headers: `*`

### Step 5: Check Lambda Logs

If the function is being called but failing:

```bash
# View recent logs for orchestrator
aws logs tail /aws/lambda/LambdaOrchestrateUploadHandler --follow

# Or check CloudWatch Console
# CloudWatch > Log groups > /aws/lambda/LambdaOrchestrateUploadHandler
```

### Step 6: Test Function URL Directly

Test the Function URL with curl:

```bash
# Test orchestrator
curl -X POST https://YOUR_ORCHESTRATOR_URL \
  -H "Content-Type: application/json" \
  -d '{"key":"test.jpg","description":"test","content":""}'
```

If this fails, the issue is with the Lambda function itself, not the frontend.

### Step 7: Rebuild and Redeploy Lambda

If the Lambda code was updated, rebuild and redeploy:

```bash
# Rebuild orchestrator
cd LambdaOrchestrateUploadHandler
mvn clean package

# Then run terraform apply to update the function
cd ../terraform
terraform apply
```

### Step 8: Check Network Connectivity

- Verify you're not behind a firewall blocking AWS endpoints
- Check if you're using a VPN that might interfere
- Try from a different network

### Step 9: Verify CORS Configuration

The Lambda handler now includes CORS headers, but also verify in Terraform:

Check `terraform/19-lambda_orchestrate_upload.tf`:
```hcl
cors {
  allow_credentials = false
  allow_origins     = ["*"]
  allow_methods     = ["*"]
  allow_headers     = ["*"]
}
```

### Common Issues

1. **URLs are outdated**: Lambda Function URLs change when functions are recreated
2. **CORS not configured**: Function URL CORS must be set in Terraform
3. **Lambda not deployed**: Run `terraform apply` to deploy
4. **Wrong region**: URLs must match the AWS region (ap-southeast-2)
5. **Function doesn't exist**: Check if Terraform created the functions

### Quick Fix Script

Create a script to update URLs automatically:

```bash
#!/bin/bash
# update_urls.sh

cd terraform
ORCHESTRATOR=$(terraform output -raw orchestrator_function_url)
LIST=$(terraform output -raw get_list_of_objects_function_url)
DOWNLOAD=$(terraform output -raw get_objects_function_url)
DELETE=$(terraform output -raw delete_objects_function_url)
THUMBNAIL=$(terraform output -raw get_resized_image_function_url)

cd ..
sed -i "s|const ORCHESTRATOR_URL = \".*\";|const ORCHESTRATOR_URL = \"$ORCHESTRATOR\";|" index.html
sed -i "s|const LIST_URL = \".*\";|const LIST_URL = \"$LIST\";|" index.html
sed -i "s|const DOWNLOAD_URL = \".*\";|const DOWNLOAD_URL = \"$DOWNLOAD\";|" index.html
sed -i "s|const DELETE_URL = \".*\";|const DELETE_URL = \"$DELETE\";|" index.html
sed -i "s|const THUMBNAIL_URL = \".*\";|const THUMBNAIL_URL = \"$THUMBNAIL\";|" index.html

echo "URLs updated in index.html"
```

## Still Having Issues?

1. Check browser console (F12) for detailed error messages
2. Check Network tab to see the actual HTTP request/response
3. Verify AWS credentials and permissions
4. Check if Lambda functions have proper IAM roles
5. Review CloudWatch logs for Lambda execution errors







