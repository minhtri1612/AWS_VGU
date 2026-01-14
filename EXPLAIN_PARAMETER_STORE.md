# 🔐 SYSTEM MANAGER PARAMETER STORE - DÙNG ĐỂ LÀM GÌ?

## 🎯 MỤC ĐÍCH CHÍNH

**Lưu trữ secrets và configuration an toàn** thay vì hardcode trong code hoặc environment variables.

## 📊 SO SÁNH

### ❌ TRƯỚC ĐÂY (KHÔNG AN TOÀN):
```java
// Hardcode trong code - RẤT NGUY HIỂM!
private static final String SECRET_KEY = "my-secret-key-123";

// Hoặc trong env var - vẫn không an toàn
private static final String SECRET_KEY = System.getenv("SECRET_KEY");
```

**Vấn đề:**
- Secret key có thể bị leak trong code
- Phải redeploy Lambda mỗi khi đổi key
- Khó quản lý nhiều secrets

### ✅ BÂY GIỜ (AN TOÀN):
```java
// Lấy từ Parameter Store - AN TOÀN!
String secretKey = getSecretKeyFromParameterStore(logger);
```

**Lợi ích:**
- ✅ Encrypted với KMS (AWS tự động mã hóa)
- ✅ Không hardcode trong code
- ✅ Dễ rotate key (đổi trong Parameter Store, không cần redeploy)
- ✅ Có versioning (track lịch sử thay đổi)
- ✅ Có audit log (ai đã access khi nào)

## 🔍 TRONG PROJECT CỦA BẠN

### Parameter: `keytokenhash`
- **Type:** SecureString (encrypted)
- **Value:** SECRET_KEY để generate token (HMAC-SHA256)
- **Dùng để:** Generate và verify token từ email

### Cách Lambda lấy:
```
Lambda Code 
  → HTTP GET localhost:2773/.../keytokenhash
  → Lambda Extension (tự động authenticate, cache)
  → Parameter Store → Trả về SECRET_KEY (decrypted)
```

## 📝 VÍ DỤ THỰC TẾ

**Trước:**
```java
// Trong LambdaGenerateToken.java
private static final String SECRET_KEY = System.getenv("SECRET_KEY");
String token = generateSecureToken(email, SECRET_KEY, logger);
```

**Sau:**
```java
// Trong LambdaGenerateToken.java
String secretKey = getSecretKeyFromParameterStore(logger);
String token = generateSecureToken(email, secretKey, logger);
```

## 🎯 TÓM TẮT

**Parameter Store = Kho lưu trữ secrets an toàn**
- Giống như "vault" để cất password, keys
- AWS tự động mã hóa và quản lý
- Lambda chỉ cần gọi HTTP để lấy (không cần hardcode)

**Trong project của bạn:**
- Lưu `SECRET_KEY` để generate token
- Thay thế environment variable
- An toàn hơn, dễ quản lý hơn
