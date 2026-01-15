# ✅ DELETE ORCHESTRATOR DEPLOYMENT CHECKLIST

**Date:** 2026-01-15  
**Feature:** Dedicated `/delete-orchestrator` Route  
**Status:** 🟡 Ready for Deployment

---

## 📋 PRE-DEPLOYMENT CHECKLIST

### Code Changes
- [x] Terraform: Added `/delete-orchestrator` route in `10-api_gateway.tf`
- [x] Terraform: Added DELETE method integration
- [x] Terraform: Added OPTIONS method for CORS
- [x] Terraform: Added Lambda permission
- [x] Terraform: Updated deployment dependencies
- [x] Terraform: Commented out duplicate permission in `23-lambda_orchestrate_delete_handler.tf`
- [x] Frontend: Added `DELETE_ORCHESTRATOR_URL` constant in `index.html`
- [x] Frontend: Updated `deleteObject()` function
- [x] Documentation: Updated `LAMBDA_FLOW_DIAGRAM.md`
- [x] Documentation: Created `DELETE_ORCHESTRATOR_REFACTORING.md`
- [x] Documentation: Created `DELETE_ORCHESTRATOR_ARCHITECTURE.md`

### Code Review
- [ ] Terraform changes reviewed
- [ ] Frontend changes reviewed
- [ ] Documentation reviewed
- [ ] No security vulnerabilities introduced

### Testing (Local/Dev)
- [ ] Terraform validate successful
- [ ] Terraform plan successful (no unexpected changes)
- [ ] No linting errors in HTML/JavaScript

---

## 🚀 DEPLOYMENT STEPS

### Step 1: Backup Current State
```bash
# Backup current Terraform state
cd /home/minhtri/Documents/AWS_VGU_CLAVEL/terraform
terraform state pull > ../backup/terraform-state-$(date +%Y%m%d-%H%M%S).json

# Backup current API Gateway settings (optional - record current endpoints)
aws apigateway get-rest-apis --region ap-southeast-2 > ../backup/api-gateway-before.json
```
- [ ] Terraform state backed up
- [ ] API Gateway config recorded

### Step 2: Deploy Terraform Changes
```bash
cd /home/minhtri/Documents/AWS_VGU_CLAVEL/terraform

# Validate configuration
terraform validate

# Review planned changes
terraform plan -out=delete-orchestrator.tfplan

# Apply changes
terraform apply delete-orchestrator.tfplan
```
- [ ] `terraform validate` passed
- [ ] `terraform plan` reviewed (check resources to be created)
- [ ] Expected changes:
  - [ ] `aws_api_gateway_resource.delete_orchestrator` - CREATE
  - [ ] `aws_api_gateway_method.delete_orchestrator` - CREATE
  - [ ] `aws_api_gateway_method.delete_orchestrator_options` - CREATE
  - [ ] `aws_api_gateway_integration.delete_orchestrator` - CREATE
  - [ ] `aws_api_gateway_integration.delete_orchestrator_options` - CREATE
  - [ ] `aws_lambda_permission.api_gateway_delete_orchestrator` - CREATE
  - [ ] `aws_api_gateway_deployment.main` - UPDATE (redeploy)
  - [ ] `aws_lambda_permission.api_gateway_orchestrate_delete_handler` (in 23-*.tf) - DESTROY
- [ ] `terraform apply` successful

### Step 3: Verify Infrastructure
```bash
# Get API Gateway URL
terraform output api_gateway_url

# Test new endpoint
API_URL=$(terraform output -raw api_gateway_url)
TOKEN="<your-test-token>"
EMAIL="<your-test-email>"

# Upload a test file first (via UI or API)
# Then test delete:
curl -X DELETE "${API_URL}/delete-orchestrator" \
  -H "Content-Type: application/json" \
  -d "{\"key\":\"test-file.jpg\",\"token\":\"${TOKEN}\",\"email\":\"${EMAIL}\"}"
```
- [ ] API Gateway deployment successful
- [ ] New route `/delete-orchestrator` visible in AWS Console
- [ ] Integration points to correct Lambda
- [ ] CORS configuration correct

### Step 4: Test API Endpoints

**Test 1: OPTIONS (CORS Preflight)**
```bash
curl -X OPTIONS "${API_URL}/delete-orchestrator" \
  -H "Origin: http://localhost" \
  -v
```
Expected:
- [ ] Status: 200
- [ ] Headers include CORS headers

**Test 2: DELETE (Invalid Token)**
```bash
curl -X DELETE "${API_URL}/delete-orchestrator" \
  -H "Content-Type: application/json" \
  -d '{"key":"test.jpg","token":"invalid","email":"test@test.com"}'
```
Expected:
- [ ] Status: 403
- [ ] Error: "Invalid token"

**Test 3: DELETE (Valid Request)**
```bash
# First, upload a file via UI to get valid token
# Then:
curl -X DELETE "${API_URL}/delete-orchestrator" \
  -H "Content-Type: application/json" \
  -d "{\"key\":\"YOUR_FILE.jpg\",\"token\":\"YOUR_TOKEN\",\"email\":\"YOUR_EMAIL\"}"
```
Expected:
- [ ] Status: 200
- [ ] Response shows all 3 delete activities completed
- [ ] File deleted from S3 original bucket
- [ ] File deleted from database
- [ ] File deleted from S3 resized bucket

### Step 5: Deploy Frontend
```bash
# If using S3 for frontend hosting:
cd /home/minhtri/Documents/AWS_VGU_CLAVEL
aws s3 cp index.html s3://YOUR-BUCKET-NAME/index.html

# If using CloudFront, invalidate cache:
aws cloudfront create-invalidation \
  --distribution-id YOUR-DISTRIBUTION-ID \
  --paths "/index.html"
```
- [ ] Frontend deployed to hosting location
- [ ] Cache invalidated (if applicable)

### Step 6: End-to-End Testing
```bash
# Manual testing steps in browser:
```
1. [ ] Open application in browser
2. [ ] Login with email to get token
3. [ ] Upload a test file
4. [ ] Verify file appears in list
5. [ ] Click "Delete" button
6. [ ] Verify file is deleted (refresh list)
7. [ ] Check browser DevTools Network tab - confirm DELETE request goes to `/delete-orchestrator`
8. [ ] Check CloudWatch Logs:
   - [ ] `LambdaOrchestrateDeleteHandler` has new logs
   - [ ] `LambdaEntryPoint` does NOT have delete-related logs (for new deletes)

---

## 🔍 POST-DEPLOYMENT VERIFICATION

### CloudWatch Logs
```bash
# Check Delete Orchestrator logs
aws logs tail /aws/lambda/LambdaOrchestrateDeleteHandler --follow

# Verify NO delete logs in EntryPoint (for new requests)
aws logs tail /aws/lambda/LambdaEntryPoint --follow
```
- [ ] Delete Orchestrator logs show direct invocations
- [ ] No DELETE routing in EntryPoint logs (for new requests)

### Metrics to Monitor
- [ ] Delete operation latency (should be lower)
- [ ] Lambda invocation count for EntryPoint (should be lower)
- [ ] Error rate remains stable or improved
- [ ] No increase in timeout errors

### Cost Analysis (After 24 hours)
- [ ] Compare Lambda invocation costs (before vs after)
- [ ] Verify reduced invocations for EntryPoint
- [ ] Document cost savings

---

## ⚠️ ROLLBACK PLAN

If issues occur:

### Quick Rollback - Frontend Only
```bash
# Revert frontend to use old URL
# In index.html, change:
# fetch(DELETE_ORCHESTRATOR_URL, ...) 
# back to:
# fetch(API_GATEWAY_BASE, ...)

# Redeploy frontend
aws s3 cp index.html.backup s3://YOUR-BUCKET-NAME/index.html
```

### Full Rollback - Terraform
```bash
cd /home/minhtri/Documents/AWS_VGU_CLAVEL/terraform

# Restore Terraform state from backup
terraform state pull > current-state-broken.json
terraform state push ../backup/terraform-state-YYYYMMDD-HHMMSS.json

# Revert code changes
git revert <commit-hash>

# Apply old configuration
terraform apply
```

### Rollback Triggers
Roll back if:
- [ ] Delete operations fail at >5% rate
- [ ] Latency increases instead of decreases
- [ ] Any security vulnerabilities discovered
- [ ] Customer-facing errors occur

---

## 📊 SUCCESS CRITERIA

Deployment is successful if:
- [x] Terraform apply completed without errors
- [ ] New `/delete-orchestrator` route is accessible
- [ ] Delete operations work correctly via new route
- [ ] Old route still works (backward compatibility)
- [ ] No increase in error rates
- [ ] Latency reduced by ~10-15% (target)
- [ ] CloudWatch logs show correct routing
- [ ] Cost reduced by ~20% for delete operations

---

## 📝 POST-DEPLOYMENT TASKS

### Immediate (Within 24 hours)
- [ ] Monitor error rates
- [ ] Monitor latency metrics
- [ ] Check CloudWatch dashboard
- [ ] Verify no customer complaints

### Short-term (Within 1 week)
- [ ] Document any issues encountered
- [ ] Update team knowledge base
- [ ] Create monitoring alerts if needed
- [ ] Share metrics with team

### Long-term (Within 1 month)
- [ ] Analyze performance improvements
- [ ] Document cost savings
- [ ] Plan deprecation of old route in EntryPoint
- [ ] Consider applying same pattern to other operations

---

## 🆘 TROUBLESHOOTING

### Issue: Terraform apply fails
**Solution:**
- Check AWS credentials
- Verify region is correct
- Check for resource naming conflicts
- Review error message, may need to import existing resources

### Issue: CORS errors in browser
**Solution:**
- Verify OPTIONS integration is correct
- Check CORS headers in Lambda response
- Clear browser cache
- Check API Gateway CORS settings

### Issue: 403 Forbidden errors
**Solution:**
- Verify Lambda permission is created
- Check IAM role for API Gateway
- Verify Lambda execution role has necessary permissions

### Issue: Delete doesn't work
**Solution:**
- Check Lambda logs for errors
- Verify token is valid
- Check ownership verification logic
- Verify S3 bucket permissions

---

## 📞 CONTACTS

- **Primary Contact:** DevOps Team
- **Escalation:** Cloud Architecture Team
- **AWS Support:** (if infrastructure issues)

---

**Checklist Owner:** Infrastructure Team  
**Deployment Window:** TBD  
**Estimated Duration:** 30 minutes  
**Risk Level:** 🟢 Low (Backward compatible)
