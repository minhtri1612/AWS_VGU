# 📋 HƯỚNG DẪN SETUP SYSTEM MANAGER PARAMETER STORE

## 🎯 MỤC ĐÍCH
Lưu trữ `SECRET_KEY` an toàn trong Parameter Store thay vì environment variable:
- ✅ Bảo mật hơn (encrypted với KMS)
- ✅ Dễ quản lý (rotate key không cần redeploy)
- ✅ Không hardcode trong code

## ✅ ĐÃ HOÀN THÀNH

### 1. Parameter Store
- ✅ Parameter `keytokenhash` đã được tạo trong AWS
- ✅ Type: `SecureString` (encrypted)
- ✅ Terraform resource: `terraform/25-ssm_parameter.tf`

### 2. IAM Policy
- ✅ Lambda role có quyền `ssm:GetParameter`
- ✅ Terraform: `terraform/3-iam.tf` → `lambda_ssm_policy`

### 3. Lambda Extension Layer
- ✅ ARN: `arn:aws:lambda:ap-southeast-2:590474943231:layer:AWS-Parameters-and-Secrets-Lambda-Extension:11`
- ✅ Đã thêm vào `LambdaTokenChecker`

### 4. Code Update
- ✅ `LambdaTokenChecker` đã được update để lấy SECRET_KEY từ Parameter Store

## ⏳ CẦN LÀM TIẾP

### 1. Update các Lambda functions khác:
Các Lambda này cũng dùng `SECRET_KEY`, cần update tương tự:
- `LambdaGenerateToken`
- `LambdaOrchestrateUploadHandler`
- `LambdaOrchestrateDeleteHandler`
- `LambdaGetPhotosDB`
- `LambdaAddPhotoDB`

### 2. Thêm Extension Layer vào các Lambda:
Trong Terraform, thêm vào mỗi Lambda function:
```terraform
layers = [local.ssm_extension_layer_arn]
```

### 3. Deploy:
```bash
cd terraform
terraform apply
```

## 🔍 CÁCH HOẠT ĐỘNG

1. **Lambda Extension** chạy trong Lambda runtime
2. Lambda code gọi HTTP: `http://localhost:2773/systemsmanager/parameters/get/?name=keytokenhash&withDecryption=true`
3. Extension tự động:
   - Authenticate với AWS
   - Lấy parameter từ SSM
   - Cache lại để giảm latency
   - Trả về cho Lambda code

## 📝 LƯU Ý

- Extension Layer **PHẢI** được thêm vào Lambda function
- IAM role **PHẢI** có quyền `ssm:GetParameter`
- Parameter name **PHẢI** đúng: `keytokenhash`
- Region **PHẢI** đúng: `ap-southeast-2`
