# 🚀 DELETE ORCHESTRATOR REFACTORING

## 📝 MOTIVATION

Trước đây, Delete Orchestrator có architecture **KHÔNG ĐỒNG NHẤT** với Upload Orchestrator:

| Aspect | Upload Orchestrator | Delete Orchestrator (OLD) |
|--------|-------------------|--------------------------|
| **Route** | `/orchestrator` (dedicated) | `/` (shared root) |
| **Access** | Direct | Via EntryPoint |
| **Frontend URL** | `ORCHESTRATOR_URL` | `API_GATEWAY_BASE` |
| **Tier** | Tier 1 (Direct) | Tier 2 (Routed) |

**Vấn đề:**
- ❌ Architecture không consistent
- ❌ Delete phải qua thêm 1 hop (EntryPoint) → tăng latency
- ❌ Khó debug/monitor (logs trộn lẫn với EntryPoint)
- ❌ Security isolation kém hơn

---

## ✅ GIẢI PHÁP

Tạo **dedicated route `/delete-orchestrator`** cho Delete Orchestrator để matching với Upload.

### Architecture MỚI:

```
UPLOAD:
Frontend → /orchestrator → LambdaOrchestrateUploadHandler → Workers

DELETE:
Frontend → /delete-orchestrator → LambdaOrchestrateDeleteHandler → Workers
```

**Lợi ích:**
- ✅ Architecture đồng nhất (consistent)
- ✅ Direct access - giảm latency
- ✅ Dễ monitor/debug (logs riêng biệt)
- ✅ Security isolation tốt hơn
- ✅ Scalability - mỗi orchestrator có riêng resource quota

---

## 🔧 THAY ĐỔI THỰC HIỆN

### 1. **Terraform - API Gateway Configuration**
   
**File:** `terraform/10-api_gateway.tf`

- ✅ Thêm `aws_api_gateway_resource.delete_orchestrator` (path: `/delete-orchestrator`)
- ✅ Thêm `aws_api_gateway_method.delete_orchestrator` (DELETE method)
- ✅ Thêm `aws_api_gateway_method.delete_orchestrator_options` (OPTIONS for CORS)
- ✅ Thêm `aws_api_gateway_integration.delete_orchestrator` (integration với Lambda)
- ✅ Thêm `aws_api_gateway_integration.delete_orchestrator_options` (CORS integration)
- ✅ Thêm `aws_lambda_permission.api_gateway_delete_orchestrator` (permission cho API Gateway invoke Lambda)
- ✅ Cập nhật `aws_api_gateway_deployment.main.depends_on` để include các integration mới

**File:** `terraform/23-lambda_orchestrate_delete_handler.tf`

- ✅ Comment out old Lambda permission (duplicate - đã move vào 10-api_gateway.tf)

### 2. **Frontend - index.html**

**Thay đổi:**
- ✅ Thêm constant `DELETE_ORCHESTRATOR_URL`
- ✅ Sửa hàm `deleteObject()` để dùng `DELETE_ORCHESTRATOR_URL` thay vì `API_GATEWAY_BASE`

**Code:**
```javascript
// Old:
fetch(API_GATEWAY_BASE, { method: 'DELETE', ... })

// New:
fetch(DELETE_ORCHESTRATOR_URL, { method: 'DELETE', ... })
```

### 3. **Documentation - LAMBDA_FLOW_DIAGRAM.md**

- ✅ Cập nhật architecture diagram để show `/delete-orchestrator` route
- ✅ Cập nhật flow DELETE FILE để reflect direct access
- ✅ Cập nhật API Gateway Routes section
- ✅ Cập nhật Entry Points classification
- ✅ Mark LambdaEntryPoint routing logic as "legacy" cho delete

---

## 📊 MIGRATION PLAN

### ⚠️ Breaking Changes:
**KHÔNG CÓ** - Backward compatible!

Lý do:
- Lambda code KHÔNG thay đổi
- Old route vẫn hoạt động (qua EntryPoint)
- Chỉ frontend sử dụng route mới

### 🚀 Deployment Steps:

1. **Deploy Terraform changes:**
   ```bash
   cd terraform
   terraform plan
   terraform apply
   ```

2. **Verify API Gateway:**
   - Route `/delete-orchestrator` đã được tạo
   - Integration với `LambdaOrchestrateDeleteHandler` đúng
   - CORS được config đúng

3. **Deploy Frontend:**
   - Upload `index.html` mới lên S3 hoặc deployment location
   - Clear cache if necessary

4. **Test:**
   - Upload 1 file
   - Delete file bằng UI mới
   - Verify trong logs: request đi trực tiếp vào `LambdaOrchestrateDeleteHandler` (không qua EntryPoint)

---

## 🧪 TESTING

### Test Cases:

1. **Delete via new route:**
   ```bash
   curl -X DELETE https://API_GATEWAY/dev/delete-orchestrator \
     -H "Content-Type: application/json" \
     -d '{"key":"test.jpg","token":"xxx","email":"test@test.com"}'
   ```

2. **CORS preflight:**
   ```bash
   curl -X OPTIONS https://API_GATEWAY/dev/delete-orchestrator \
     -H "Origin: http://localhost"
   ```

3. **Verify logs:**
   - CloudWatch logs cho `LambdaOrchestrateDeleteHandler` nên show direct invocation
   - KHÔNG có logs trong `LambdaEntryPoint` cho delete operations

---

## 📈 METRICS TO MONITOR

Pre-deployment vs Post-deployment:

| Metric | Before (via EntryPoint) | After (Direct) |
|--------|------------------------|----------------|
| Avg Latency | ~300ms | ~200ms (target) |
| Cold Start | 2 hops | 1 hop |
| Error Rate | Mixed logs | Isolated logs |
| Cost | 2 Lambda invocations | 1 Lambda invocation |

---

## 🔮 FUTURE IMPROVEMENTS

1. **Deprecate EntryPoint routing for DELETE:**
   - Remove DELETE routing logic from `LambdaEntryPoint`
   - Update documentation to mark old route as deprecated

2. **Apply same pattern to other operations:**
   - Consider `/get-orchestrator` for download operations
   - Consider `/list-orchestrator` for list operations

3. **Add API Gateway caching:**
   - Cache LIST operations
   - Cache GET operations for thumbnails

---

## 📚 REFERENCES

- PR: #XXX (to be created)
- Architecture Discussion: (discussion link)
- Related Issues:
  - Architecture Consistency: #XXX
  - Performance Optimization: #XXX

---

## ✅ CHECKLIST

- [x] Terraform changes implemented
- [x] Frontend updated
- [x] Documentation updated
- [x] Testing plan defined
- [ ] Terraform apply successful
- [ ] Frontend deployed
- [ ] End-to-end testing passed
- [ ] Monitoring dashboards updated
- [ ] Team notified

---

**Created:** 2026-01-15  
**Author:** Infrastructure Team  
**Status:** Implementation Complete - Ready for Deployment
