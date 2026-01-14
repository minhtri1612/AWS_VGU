# 🔄 FLOW CỦA CÁC LAMBDA FUNCTIONS

## 📊 TỔNG QUAN KIẾN TRÚC

```
Frontend (index.html)
    ↓
API Gateway (/auth, /orchestrator, /{proxy+})
    ↓
┌─────────────────────────────────────────────────────────┐
│  LAYER 1: ENTRY POINTS                                  │
│  - LambdaEntryPoint (router chính)                     │
│  - LambdaGenerateToken (/auth endpoint)                │
│  - LambdaOrchestrateUploadHandler (/orchestrator)       │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│  LAYER 2: ORCHESTRATORS                                 │
│  - LambdaOrchestrateUploadHandler (upload workflow)     │
│  - LambdaOrchestrateDeleteHandler (delete workflow)    │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│  LAYER 3: WORKER FUNCTIONS                              │
│  - LambdaAddPhotoDB (database)                         │
│  - LambdaGetPhotosDB (database)                         │
│  - LambdaUploadObjects (S3)                             │
│  - LambdaResizeWrapper (resize images)                  │
│  - LambdaGetObjects (download)                          │
│  - LambdaGetResizedImage (thumbnails)                   │
│  - LambdaDeleteObjects (S3)                             │
│  - LambdaDeleteResizedObject (S3)                       │
│  - LambdaGetListOfObjects (list)                        │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│  LAYER 4: SUPPORT SERVICES                              │
│  - LambdaTokenChecker (verify token - có thể unused)   │
│  - LambdaResize (image processing - triggered by S3)   │
└─────────────────────────────────────────────────────────┘
```

## 🎯 CHI TIẾT TỪNG LAMBDA

### 🔵 LAYER 1: ENTRY POINTS (Xuất hiện ĐẦU TIÊN)

#### 1. **LambdaEntryPoint** ⭐ (ROUTER CHÍNH)
- **Vai trò**: Router trung tâm, nhận request từ API Gateway và route đến Lambda phù hợp
- **Khi nào xuất hiện**: MỌI request từ API Gateway (trừ /auth và /orchestrator)
- **Flow**:
  ```
  API Gateway → LambdaEntryPoint → Phân tích action → Route đến Lambda phù hợp
  ```
- **Routing logic**:
  - `DELETE` → LambdaOrchestrateDeleteHandler
  - `upload` → LambdaOrchestrateUploadHandler
  - `list` → LambdaGetListOfObjects
  - `get_resized` → LambdaGetResizedImage
  - `get_photos_db` → LambdaGetPhotosDB
  - `add_photo_db` → LambdaAddPhotoDB
  - Mặc định → LambdaGetObjects (download)

#### 2. **LambdaGenerateToken** 🔐 (AUTH ENDPOINT)
- **Vai trò**: Generate và verify token cho authentication
- **Khi nào xuất hiện**: Khi user login (frontend gọi `/auth`)
- **Flow**:
  ```
  Frontend → API Gateway /auth → LambdaGenerateToken
  ```
- **Actions**:
  - `request_token`: Generate token từ email
  - `verify_token`: Verify token có hợp lệ không
- **Không invoke Lambda khác**: Tự xử lý hoàn toàn

#### 3. **LambdaOrchestrateUploadHandler** 📤 (UPLOAD ORCHESTRATOR)
- **Vai trò**: Điều phối workflow upload (DB → S3 original → Resize → S3 resized)
- **Khi nào xuất hiện**: Khi user upload file (frontend gọi `/orchestrator`)
- **Flow**:
  ```
  Frontend → API Gateway /orchestrator → LambdaOrchestrateUploadHandler
    ↓
  Step Functions State Machine
    ↓
  ┌─────────────────────────────────────┐
  │ Activity 1: LambdaAddPhotoDB        │ (Insert vào DB)
  │ Activity 2: LambdaUploadObjects     │ (Upload original S3)
  │ Activity 3: LambdaResizeWrapper    │ (Resize + Upload resized S3)
  └─────────────────────────────────────┘
  ```

### 🟢 LAYER 2: ORCHESTRATORS

#### 4. **LambdaOrchestrateDeleteHandler** 🗑️
- **Vai trò**: Điều phối xóa file (parallel: S3 original + DB + S3 resized)
- **Khi nào xuất hiện**: Khi user delete file
- **Flow**:
  ```
  LambdaEntryPoint → LambdaOrchestrateDeleteHandler
    ↓
  Parallel execution:
  ├─ Delete từ S3 original bucket
  ├─ Delete từ DB (Photos table)
  └─ Delete từ S3 resized bucket
  ```
- **Invoke**: Không invoke Lambda khác, tự xử lý S3 và DB

### 🟡 LAYER 3: WORKER FUNCTIONS

#### 5. **LambdaAddPhotoDB** 💾
- **Vai trò**: Thêm record vào database (Photos table)
- **Khi nào xuất hiện**: Trong upload workflow (Step Functions Activity 1)
- **Flow**:
  ```
  LambdaOrchestrateUploadHandler → Step Functions → LambdaAddPhotoDB
  ```
- **Chức năng**:
  - Verify token
  - Insert vào DB: Description, S3Key, Email
  - Tự động tạo DB và table nếu chưa có

#### 6. **LambdaGetPhotosDB** 📋
- **Vai trò**: Lấy danh sách photos từ database
- **Khi nào xuất hiện**: Khi user click "List" button
- **Flow**:
  ```
  Frontend → API Gateway → LambdaEntryPoint → LambdaGetPhotosDB
  ```
- **Chức năng**:
  - Verify token
  - Query DB: SELECT * FROM Photos
  - Return JSON array

#### 7. **LambdaUploadObjects** ☁️
- **Vai trò**: Upload file lên S3 bucket (original)
- **Khi nào xuất hiện**: Trong upload workflow (Step Functions Activity 2)
- **Flow**:
  ```
  LambdaOrchestrateUploadHandler → Step Functions → LambdaUploadObjects
  ```
- **Chức năng**: Upload file content lên S3

#### 8. **LambdaResizeWrapper** 🖼️
- **Vai trò**: Resize image và upload lên S3 resized bucket
- **Khi nào xuất hiện**: Trong upload workflow (Step Functions Activity 3)
- **Flow**:
  ```
  LambdaOrchestrateUploadHandler → Step Functions → LambdaResizeWrapper
    ↓
  LambdaResizeWrapper → LambdaResize (invoke)
  ```
- **Chức năng**:
  - Download từ S3 original
  - Resize image
  - Upload lên S3 resized bucket

#### 9. **LambdaGetObjects** ⬇️
- **Vai trò**: Download file từ S3
- **Khi nào xuất hiện**: Khi user download file
- **Flow**:
  ```
  Frontend → API Gateway → LambdaEntryPoint → LambdaGetObjects
  ```
- **Chức năng**:
  - Verify token
  - Get object từ S3
  - Return file content (base64)

#### 10. **LambdaGetResizedImage** 🖼️
- **Vai trò**: Lấy thumbnail (resized image) từ S3
- **Khi nào xuất hiện**: Khi hiển thị thumbnail trong list
- **Flow**:
  ```
  Frontend → API Gateway → LambdaEntryPoint → LambdaGetResizedImage
  ```
- **Chức năng**: Get resized image từ S3 resized bucket

#### 11. **LambdaDeleteObjects** 🗑️
- **Vai trò**: Xóa object từ S3 original bucket
- **Khi nào xuất hiện**: Trong delete workflow (parallel execution)
- **Flow**:
  ```
  LambdaOrchestrateDeleteHandler → LambdaDeleteObjects (parallel)
  ```
- **Chức năng**: Delete object từ S3

#### 12. **LambdaDeleteResizedObject** 🗑️
- **Vai trò**: Xóa object từ S3 resized bucket
- **Khi nào xuất hiện**: Trong delete workflow (parallel execution)
- **Flow**:
  ```
  LambdaOrchestrateDeleteHandler → LambdaDeleteResizedObject (parallel)
  ```
- **Chức năng**: Delete resized object từ S3

#### 13. **LambdaGetListOfObjects** 📝
- **Vai trò**: List objects từ S3 bucket
- **Khi nào xuất hiện**: Khi user list files từ S3 (không phải DB)
- **Flow**:
  ```
  Frontend → API Gateway → LambdaEntryPoint → LambdaGetListOfObjects
  ```
- **Chức năng**: List objects từ S3 bucket

### 🔴 LAYER 4: SUPPORT SERVICES

#### 14. **LambdaTokenChecker** 🔍
- **Vai trò**: Verify token (có thể không còn được dùng)
- **Khi nào xuất hiện**: Có thể được invoke bởi LambdaGetObject (nhưng thực tế LambdaGetObject tự verify)
- **Status**: ⚠️ Có thể là legacy/unused code

#### 15. **LambdaResize** 🖼️
- **Vai trò**: Resize image (core image processing)
- **Khi nào xuất hiện**: 
  - Được invoke bởi LambdaResizeWrapper
  - Hoặc được trigger bởi S3 event (khi upload vào source bucket)
- **Flow**:
  ```
  LambdaResizeWrapper → LambdaResize
  HOẶC
  S3 Upload Event → LambdaResize
  ```

## 🔄 FLOW CHI TIẾT THEO USE CASE

### 1. 📤 UPLOAD FILE
```
Frontend upload file
    ↓
POST /orchestrator (API Gateway)
    ↓
LambdaOrchestrateUploadHandler
    ↓ Verify token
    ↓ Extract email from token
    ↓
Step Functions State Machine
    ↓
┌─────────────────────────────────────────────┐
│ Activity 1: LambdaAddPhotoDB               │
│   → Insert vào DB (Description, S3Key, Email)
└─────────────────────────────────────────────┘
    ↓ (nếu thành công)
┌─────────────────────────────────────────────┐
│ Activity 2: LambdaUploadObjects            │
│   → Upload original file lên S3
└─────────────────────────────────────────────┘
    ↓ (nếu thành công)
┌─────────────────────────────────────────────┐
│ Activity 3: LambdaResizeWrapper            │
│   → Download từ S3
│   → Invoke LambdaResize
│   → Upload resized lên S3 resized bucket
└─────────────────────────────────────────────┘
    ↓
Return success response
```

### 2. 📋 LIST PHOTOS
```
Frontend click "List" button
    ↓
GET /?format=photos (API Gateway)
    ↓
LambdaEntryPoint (detect action = "get_photos_db")
    ↓
LambdaGetPhotosDB
    ↓ Verify token
    ↓ Query DB: SELECT * FROM Photos
    ↓
Return JSON array
    ↓
Frontend hiển thị table
    ↓ (cho mỗi photo)
GET /?action=get_resized&key=xxx
    ↓
LambdaEntryPoint → LambdaGetResizedImage
    ↓
Return thumbnail image
```

### 3. ⬇️ DOWNLOAD FILE
```
Frontend click "Download" button
    ↓
GET /?key=xxx (API Gateway)
    ↓
LambdaEntryPoint (default route)
    ↓
LambdaGetObjects
    ↓ Verify token
    ↓ Get object từ S3
    ↓
Return file content (base64)
```

### 4. 🗑️ DELETE FILE
```
Frontend click "Delete" button
    ↓
DELETE /?key=xxx (API Gateway)
    ↓
LambdaEntryPoint (detect action = "delete")
    ↓
LambdaOrchestrateDeleteHandler
    ↓ Verify token
    ↓ Verify ownership (chỉ owner mới delete được)
    ↓
Parallel execution:
├─ Delete từ S3 original bucket
├─ Delete từ DB (Photos table)
└─ Delete từ S3 resized bucket
    ↓
Return success response
```

### 5. 🔐 LOGIN
```
Frontend nhập email → Click "Login"
    ↓
POST /auth (API Gateway)
    ↓
LambdaGenerateToken (action = "request_token")
    ↓ Get SECRET_KEY từ Parameter Store
    ↓ Generate token = HMAC-SHA256(email, SECRET_KEY)
    ↓
Return token
    ↓
Frontend auto-fill token
    ↓
User click "Login" lại
    ↓
POST /auth (API Gateway)
    ↓
LambdaGenerateToken (action = "verify_token")
    ↓ Verify token
    ↓
Return valid/invalid
```

## 🔗 KẾT NỐI GIỮA CÁC LAMBDA

### Direct Invocation (Lambda → Lambda)
- LambdaEntryPoint → Tất cả Lambda khác (trừ GenerateToken và OrchestrateUploadHandler)
- LambdaOrchestrateUploadHandler → Step Functions → LambdaAddPhotoDB, LambdaUploadObjects, LambdaResizeWrapper
- LambdaResizeWrapper → LambdaResize

### Step Functions Orchestration
- LambdaOrchestrateUploadHandler → Step Functions State Machine
  - Activity 1: LambdaAddPhotoDB
  - Activity 2: LambdaUploadObjects
  - Activity 3: LambdaResizeWrapper

### S3 Event Triggers
- S3 Upload → LambdaResize (optional, có thể được trigger tự động)

### API Gateway Routes
- `/auth` → LambdaGenerateToken
- `/orchestrator` → LambdaOrchestrateUploadHandler
- `/{proxy+}` → LambdaEntryPoint → Route đến Lambda phù hợp

## 📊 THỨ TỰ XUẤT HIỆN

1. **Đầu tiên**: LambdaEntryPoint, LambdaGenerateToken, LambdaOrchestrateUploadHandler (Entry Points)
2. **Tiếp theo**: LambdaOrchestrateDeleteHandler (Orchestrator)
3. **Sau đó**: Các Worker Functions (LambdaAddPhotoDB, LambdaGetPhotosDB, etc.)
4. **Cuối cùng**: Support Services (LambdaTokenChecker, LambdaResize)

## 🎯 TÓM TẮT

- **Entry Points**: LambdaEntryPoint, LambdaGenerateToken, LambdaOrchestrateUploadHandler
- **Orchestrators**: LambdaOrchestrateUploadHandler, LambdaOrchestrateDeleteHandler
- **Workers**: LambdaAddPhotoDB, LambdaGetPhotosDB, LambdaUploadObjects, LambdaResizeWrapper, LambdaGetObjects, LambdaGetResizedImage, LambdaDeleteObjects, LambdaDeleteResizedObject, LambdaGetListOfObjects
- **Support**: LambdaTokenChecker (có thể unused), LambdaResize

